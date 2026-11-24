package dev.leafcraft.goldleaf.compute;

import io.papermc.paper.compute.ComputeService;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Built-in audited mass explosion raycasting kernel ({@code paper:mass_explosion_raycast}).
 *
 * <p>Evaluates a batch of non-overlapping vanilla Minecraft explosions in parallel over an
 * immutable 3D blast-resistance snapshot. Each explosion casts the exact vanilla
 * {@code 16x16x16} cube-surface ray fan ({@value #RAYS_PER_EXPLOSION} rays, step {@code 0.3f},
 * attenuation {@code 0.225f}, resistance decay {@code (resistance + 0.3f) * 0.3f}) and marks
 * destroyed voxels in a compact atomic bitset ({@code 1 bit/voxel}).</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017
 * @trace S-002, S-004, S-005
 */
@NullMarked
public final class MassExplosionRaycastKernel {

    public static final String KERNEL_ID = ComputeService.BuiltInKernels.MASS_EXPLOSION_RAYCAST;
    public static final String KERNEL_NAME = "mass_explosion_raycast";
    public static final int RAYS_PER_EXPLOSION = 1352;
    public static final int EXPLOSION_RECORD_BYTES = 20;
    public static final int DEFAULT_MAX_INPUT_BYTES = 8 * 1024 * 1024;
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;
    public static final int DEFAULT_MAX_GLOBAL_WORK_ITEMS = 512 * RAYS_PER_EXPLOSION;

    public static final float AIR_RESISTANCE = -1.0f;

    /**
     * Single explosion descriptor in grid-local coordinates.
     */
    public record ExplosionSpec(float relX, float relY, float relZ, float power, int seed) {
        public ExplosionSpec {
            if (!Float.isFinite(relX) || !Float.isFinite(relY) || !Float.isFinite(relZ)) {
                throw new IllegalArgumentException("Explosion coordinates must be finite");
            }
            if (!Float.isFinite(power) || power <= 0.0f) {
                throw new IllegalArgumentException("Explosion power must be finite and > 0");
            }
        }
    }

    /**
     * Destroyed voxel coordinate relative to the blast-resistance grid origin.
     */
    public record VoxelPos(int x, int y, int z) {
    }

    /**
     * Packed direct-buffer batch ready to submit to {@link ComputeService.ComputeKernelHandle}.
     */
    public record PackedBatch(
        int sizeX,
        int sizeY,
        int sizeZ,
        int explosionCount,
        ByteBuffer inputBuffer,
        int inputBytes,
        ByteBuffer outputBuffer,
        int outputBytes,
        int[] params,
        int globalWorkItems
    ) {
        public ComputeService.DispatchRequest toDispatchRequest() {
            return new ComputeService.DispatchRequest(
                this.inputBuffer,
                this.inputBytes,
                this.outputBuffer,
                this.outputBytes,
                this.params,
                this.globalWorkItems,
                0,
                true
            );
        }

        public List<VoxelPos> decodeDestroyedVoxels() {
            return unpackDestroyedVoxels(this.outputBuffer, this.sizeX, this.sizeY, this.sizeZ);
        }

        public int countDestroyedVoxels() {
            return countDestroyedBits(this.outputBuffer, this.sizeX * this.sizeY * this.sizeZ);
        }
    }

    public static final String OPENCL_SOURCE = """
        static inline void ray_index_to_surface(const int ray_idx, int* rx, int* ry, int* rz) {
            if (ray_idx < 256) {
                *rx = 0;
                *ry = ray_idx >> 4;
                *rz = ray_idx & 15;
            } else if (ray_idx < 512) {
                const int k = ray_idx - 256;
                *rx = 15;
                *ry = k >> 4;
                *rz = k & 15;
            } else {
                const int rem = ray_idx - 512;
                const int slice = rem / 60;
                const int edge_idx = rem - slice * 60;
                *rx = slice + 1;
                if (edge_idx < 16) {
                    *ry = 0;
                    *rz = edge_idx;
                } else if (edge_idx < 32) {
                    *ry = 15;
                    *rz = edge_idx - 16;
                } else if (edge_idx < 46) {
                    *ry = (edge_idx - 32) + 1;
                    *rz = 0;
                } else {
                    *ry = (edge_idx - 46) + 1;
                    *rz = 15;
                }
            }
        }

        static inline float ray_rand01(const uint seed, const uint ray_idx) {
            uint h = seed ^ (ray_idx * 0x9E3779B9u);
            h ^= h >> 16;
            h *= 0x85EBCA6Bu;
            h ^= h >> 13;
            h *= 0xC2B2AE35u;
            h ^= h >> 16;
            return (float)(h & 0x00FFFFFFu) * (1.0f / 16777216.0f);
        }

        __kernel void mass_explosion_raycast(
            __global const uchar* in_buf,
            __global uchar* state_buf,
            __global uchar* out_buf,
            __constant const int* params,
            const int param_count,
            const int global_items
        ) {
            (void)state_buf;
            const int gid = (int)get_global_id(0);
            if (gid >= global_items || param_count < 5) {
                return;
            }
            const int size_x = params[0];
            const int size_y = params[1];
            const int size_z = params[2];
            const int explosion_count = params[3];
            const int grid_floats = params[4];

            const int exp_idx = gid / 1352;
            const int ray_idx = gid - exp_idx * 1352;
            if (exp_idx >= explosion_count) {
                return;
            }

            __global const float* resistances = (__global const float*)in_buf;
            __global const uint* exp_words = (__global const uint*)(in_buf + ((size_t)grid_floats * 4u));
            const size_t base = (size_t)exp_idx * 5u;
            float px = as_float(exp_words[base + 0u]);
            float py = as_float(exp_words[base + 1u]);
            float pz = as_float(exp_words[base + 2u]);
            const float power = as_float(exp_words[base + 3u]);
            const uint seed = exp_words[base + 4u];

            int rx, ry, rz;
            ray_index_to_surface(ray_idx, &rx, &ry, &rz);
            const float dx = ((float)rx / 15.0f) * 2.0f - 1.0f;
            const float dy = ((float)ry / 15.0f) * 2.0f - 1.0f;
            const float dz = ((float)rz / 15.0f) * 2.0f - 1.0f;
            const float len = sqrt(dx * dx + dy * dy + dz * dz);
            const float step_x = (dx / len) * 0.3f;
            const float step_y = (dy / len) * 0.3f;
            const float step_z = (dz / len) * 0.3f;

            float intensity = power * (0.7f + ray_rand01(seed, (uint)ray_idx) * 0.6f);
            __global volatile uint* out_bits = (__global volatile uint*)out_buf;

            while (intensity > 0.0f) {
                const int bx = (int)floor(px);
                const int by = (int)floor(py);
                const int bz = (int)floor(pz);
                if (bx < 0 || bx >= size_x || by < 0 || by >= size_y || bz < 0 || bz >= size_z) {
                    break;
                }
                const int voxel_idx = (by * size_z + bz) * size_x + bx;
                const float res = resistances[voxel_idx];
                if (res >= 0.0f) {
                    intensity -= (res + 0.3f) * 0.3f;
                    if (intensity > 0.0f) {
                        atomic_or(&out_bits[voxel_idx >> 5], 1u << (voxel_idx & 31));
                    }
                }
                px += step_x;
                py += step_y;
                pz += step_z;
                intensity -= 0.225f;
            }
        }
        """;

    private MassExplosionRaycastKernel() {
    }

    public static ComputeService.KernelDescriptor builtInDescriptor(final String ownerId) {
        return new ComputeService.KernelDescriptor(
            ownerId,
            KERNEL_ID,
            KERNEL_NAME,
            OPENCL_SOURCE,
            "",
            DEFAULT_MAX_INPUT_BYTES,
            DEFAULT_MAX_OUTPUT_BYTES,
            0,
            DEFAULT_MAX_GLOBAL_WORK_ITEMS,
            MassExplosionRaycastKernel::executeCpu
        );
    }

    /**
     * Packs an immutable blast-resistance grid and a batch of explosions into direct
     * little-endian {@link ByteBuffer}s suitable for GPU or CPU dispatch.
     */
    public static PackedBatch packBatch(
        final int sizeX,
        final int sizeY,
        final int sizeZ,
        final float[] resistances,
        final List<ExplosionSpec> explosions
    ) {
        Objects.requireNonNull(resistances, "resistances");
        Objects.requireNonNull(explosions, "explosions");
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("Grid dimensions must be > 0");
        }
        final int voxelCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        if (resistances.length != voxelCount) {
            throw new IllegalArgumentException(
                "resistances.length (" + resistances.length + ") != sizeX*sizeY*sizeZ (" + voxelCount + ")"
            );
        }
        if (explosions.isEmpty()) {
            throw new IllegalArgumentException("explosions must not be empty");
        }
        final int gridBytes = Math.multiplyExact(voxelCount, Float.BYTES);
        final int expBytes = Math.multiplyExact(explosions.size(), EXPLOSION_RECORD_BYTES);
        final int inputBytes = Math.addExact(gridBytes, expBytes);
        final int bitsetWords = (voxelCount + 31) >>> 5;
        final int outputBytes = Math.multiplyExact(bitsetWords, Integer.BYTES);

        final ByteBuffer inBuf = ByteBuffer.allocateDirect(inputBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < voxelCount; i++) {
            inBuf.putFloat(resistances[i]);
        }
        for (final ExplosionSpec spec : explosions) {
            inBuf.putFloat(spec.relX());
            inBuf.putFloat(spec.relY());
            inBuf.putFloat(spec.relZ());
            inBuf.putFloat(spec.power());
            inBuf.putInt(spec.seed());
        }
        inBuf.flip();

        final ByteBuffer outBuf = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.LITTLE_ENDIAN);
        final int[] params = new int[] { sizeX, sizeY, sizeZ, explosions.size(), voxelCount };
        final int globalWorkItems = Math.multiplyExact(explosions.size(), RAYS_PER_EXPLOSION);
        return new PackedBatch(
            sizeX,
            sizeY,
            sizeZ,
            explosions.size(),
            inBuf,
            inputBytes,
            outBuf,
            outputBytes,
            params,
            globalWorkItems
        );
    }

    /**
     * Bit-exact CPU fallback matching {@link #OPENCL_SOURCE}.
     */
    public static void executeCpu(
        final ByteBuffer input,
        final @Nullable ByteBuffer state,
        final ByteBuffer output,
        final int[] params,
        final int globalWork
    ) {
        if (params.length < 5) {
            throw new IllegalArgumentException("Expected 5 params, got " + params.length);
        }
        final int sizeX = params[0];
        final int sizeY = params[1];
        final int sizeZ = params[2];
        final int explosionCount = params[3];
        final int gridFloats = params[4];

        final ByteBuffer inLe = input.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer outLe = output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int bitsetWords = (gridFloats + 31) >>> 5;
        final int[] outBits = new int[bitsetWords];
        final int expBaseOffset = gridFloats * Float.BYTES;
        final int maxGid = Math.min(globalWork, explosionCount * RAYS_PER_EXPLOSION);

        for (int gid = 0; gid < maxGid; gid++) {
            final int expIdx = gid / RAYS_PER_EXPLOSION;
            final int rayIdx = gid - expIdx * RAYS_PER_EXPLOSION;
            final int recOffset = expBaseOffset + expIdx * EXPLOSION_RECORD_BYTES;

            float px = inLe.getFloat(recOffset);
            float py = inLe.getFloat(recOffset + 4);
            float pz = inLe.getFloat(recOffset + 8);
            final float power = inLe.getFloat(recOffset + 12);
            final int seed = inLe.getInt(recOffset + 16);

            final int rx;
            final int ry;
            final int rz;
            if (rayIdx < 256) {
                rx = 0;
                ry = rayIdx >>> 4;
                rz = rayIdx & 15;
            } else if (rayIdx < 512) {
                final int k = rayIdx - 256;
                rx = 15;
                ry = k >>> 4;
                rz = k & 15;
            } else {
                final int rem = rayIdx - 512;
                final int slice = rem / 60;
                final int local = rem - slice * 60;
                rx = slice + 1;
                if (local < 16) {
                    ry = 0;
                    rz = local;
                } else if (local < 32) {
                    ry = 15;
                    rz = local - 16;
                } else if (local < 46) {
                    ry = (local - 32) + 1;
                    rz = 0;
                } else {
                    ry = (local - 46) + 1;
                    rz = 15;
                }
            }

            final float dx = (rx / 15.0f) * 2.0f - 1.0f;
            final float dy = (ry / 15.0f) * 2.0f - 1.0f;
            final float dz = (rz / 15.0f) * 2.0f - 1.0f;
            final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            final float stepX = (dx / len) * 0.3f;
            final float stepY = (dy / len) * 0.3f;
            final float stepZ = (dz / len) * 0.3f;

            float intensity = power * (0.7f + rayRand01(seed, rayIdx) * 0.6f);
            while (intensity > 0.0f) {
                final int bx = (int) Math.floor(px);
                final int by = (int) Math.floor(py);
                final int bz = (int) Math.floor(pz);
                if (bx < 0 || bx >= sizeX || by < 0 || by >= sizeY || bz < 0 || bz >= sizeZ) {
                    break;
                }
                final int voxelIdx = (by * sizeZ + bz) * sizeX + bx;
                final float res = inLe.getFloat(voxelIdx * Float.BYTES);
                if (res >= 0.0f) {
                    intensity -= (res + 0.3f) * 0.3f;
                    if (intensity > 0.0f) {
                        outBits[voxelIdx >>> 5] |= (1 << (voxelIdx & 31));
                    }
                }
                px += stepX;
                py += stepY;
                pz += stepZ;
                intensity -= 0.225f;
            }
        }

        for (int w = 0; w < bitsetWords; w++) {
            outLe.putInt(w * Integer.BYTES, outBits[w]);
        }
    }

    public static List<VoxelPos> unpackDestroyedVoxels(
        final ByteBuffer outputBuffer,
        final int sizeX,
        final int sizeY,
        final int sizeZ
    ) {
        final ByteBuffer le = outputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int voxelCount = sizeX * sizeY * sizeZ;
        final int words = (voxelCount + 31) >>> 5;
        final List<VoxelPos> result = new ArrayList<>();
        for (int w = 0; w < words; w++) {
            int bits = le.getInt(w * Integer.BYTES);
            while (bits != 0) {
                final int bit = Integer.numberOfTrailingZeros(bits);
                final int voxelIdx = (w << 5) | bit;
                if (voxelIdx < voxelCount) {
                    final int bx = voxelIdx % sizeX;
                    final int rem = voxelIdx / sizeX;
                    final int bz = rem % sizeZ;
                    final int by = rem / sizeZ;
                    result.add(new VoxelPos(bx, by, bz));
                }
                bits &= (bits - 1);
            }
        }
        return result;
    }

    public static int countDestroyedBits(final ByteBuffer outputBuffer, final int voxelCount) {
        final ByteBuffer le = outputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int words = (voxelCount + 31) >>> 5;
        int total = 0;
        for (int w = 0; w < words; w++) {
            total += Integer.bitCount(le.getInt(w * Integer.BYTES));
        }
        return total;
    }

    private static float rayRand01(final int seed, final int rayIdx) {
        int h = seed ^ (rayIdx * 0x9E3779B9);
        h ^= (h >>> 16);
        h *= 0x85EBCA6B;
        h ^= (h >>> 13);
        h *= 0xC2B2AE35;
        h ^= (h >>> 16);
        return (h & 0x00FFFFFF) * (1.0f / 16777216.0f);
    }
}
