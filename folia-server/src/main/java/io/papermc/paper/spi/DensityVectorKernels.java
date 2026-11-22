package io.papermc.paper.spi;

import ca.spottedleaf.moonrise.patches.chunk_gen.noise.NoiseGradients;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

import static ca.spottedleaf.moonrise.patches.chunk_gen.noise.NoiseGradients.PERM_MASK;
import static ca.spottedleaf.moonrise.patches.chunk_gen.noise.NoiseGradients.PERM_SHIFT;

/**
 * Flat-array, C2 SuperWord auto-vectorizable CPU density evaluation kernels.
 *
 * <p>All routines operate strictly on caller-owned {@code float[]} buffers and synchronous,
 * non-reentrant thread-local coordinate scratch arrays that are completely overwritten before
 * every read, requiring zero JVM incubator flags ({@code --add-modules=jdk.incubator.vector}).
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-001
 * @trace S-004, S-005
 */
public final class DensityVectorKernels {

    private static final int INITIAL_XZ_CAPACITY = 32;
    private static final int INITIAL_Y_CAPACITY = 512;

    private static final ThreadLocal<NoiseVolumeScratch> SCRATCH = ThreadLocal.withInitial(NoiseVolumeScratch::new);

    private DensityVectorKernels() {
    }

    /**
     * Returns this thread's reusable flat-array coordinate scratch sized for {@code (sX, sY, sZ)}.
     *
     * <p>Callers must completely overwrite {@code [0..sX)}, {@code [0..sY)}, {@code [0..sZ)}, and
     * invoke {@link NoiseVolumeScratch#buildYRuns(int)} before reading; no state is retained across
     * invocations.
     */
    public static NoiseVolumeScratch acquireScratch(final int sX, final int sY, final int sZ) {
        final NoiseVolumeScratch scratch = SCRATCH.get();
        scratch.ensureCapacity(sX, sY, sZ);
        return scratch;
    }

    /**
     * Trilinearly interpolates a coarse cell grid into a unit-step output volume.
     *
     * <p>Uses loop order {@code (cellZ, cellX, z, x, cellY, y)} so each vertical column streams
     * contiguously stride-1 through L1 cache, hoists bilinear XZ corner weights across all vertical
     * cells, and unrolls 8-voxel Y cells with independent {@link Math#fma} operations to break the
     * serial accumulation dependency chain.
     */
    public static void interpolateBlockStep(
            final float[] cellValues,
            final DensityVolume cellVolume,
            final float[] outValues,
            final DensityVolume outVolume,
            final int cellCountX,
            final int cellCountY,
            final int cellCountZ,
            final int cellSizeXz,
            final int cellSizeY,
            final float cellSizeXzInv,
            final float cellSizeYInv
    ) {
        final int outSizeX = outVolume.sizeX();
        final int outSizeY = outVolume.sizeY();
        final int outSizeZ = outVolume.sizeZ();
        final int cellVolSizeX = cellVolume.sizeX();
        final int cellVolSizeY = cellVolume.sizeY();
        final int cellVolSizeZ = cellVolume.sizeZ();

        final int baseOffsetX = cellVolume.minBlockX() - outVolume.minBlockX();
        final int baseOffsetY = cellVolume.minBlockY() - outVolume.minBlockY();
        final int baseOffsetZ = cellVolume.minBlockZ() - outVolume.minBlockZ();
        final boolean yAligned = baseOffsetY == 0 && cellCountY * cellSizeY == outSizeY;

        for (int cellZ = 0; cellZ < cellCountZ; cellZ++) {
            final int nextCellZ = Math.min(cellZ + 1, cellVolSizeZ - 1);
            final int cellOutputZ = baseOffsetZ + cellZ * cellSizeXz;
            final int z0 = Math.max(0, -cellOutputZ);
            final int z1 = Math.min(cellSizeXz, outSizeZ - cellOutputZ) - 1;
            if (z0 > z1) {
                continue;
            }

            final int cellZOffset = cellZ * cellVolSizeX;
            final int nextCellZOffset = nextCellZ * cellVolSizeX;

            for (int cellX = 0; cellX < cellCountX; cellX++) {
                final int nextCellX = Math.min(cellX + 1, cellVolSizeX - 1);
                final int cellOutputX = baseOffsetX + cellX * cellSizeXz;
                final int x0 = Math.max(0, -cellOutputX);
                final int x1 = Math.min(cellSizeXz, outSizeX - cellOutputX) - 1;
                if (x0 > x1) {
                    continue;
                }

                final int col00 = (cellX + cellZOffset) * cellVolSizeY;
                final int col10 = (nextCellX + cellZOffset) * cellVolSizeY;
                final int col01 = (cellX + nextCellZOffset) * cellVolSizeY;
                final int col11 = (nextCellX + nextCellZOffset) * cellVolSizeY;

                for (int z = z0; z <= z1; z++) {
                    final float alphaZ = z * cellSizeXzInv;
                    final float invAlphaZ = 1.0F - alphaZ;
                    final int outZOffset = (cellOutputZ + z) * outSizeX;

                    for (int x = x0; x <= x1; x++) {
                        final float alphaX = x * cellSizeXzInv;
                        final float invAlphaX = 1.0F - alphaX;

                        final float w00 = invAlphaX * invAlphaZ;
                        final float w10 = alphaX * invAlphaZ;
                        final float w01 = invAlphaX * alphaZ;
                        final float w11 = alphaX * alphaZ;

                        final int outColBase = (cellOutputX + x + outZOffset) * outSizeY;
                        float v0 = Math.fma(w00, cellValues[col00],
                                Math.fma(w10, cellValues[col10],
                                        Math.fma(w01, cellValues[col01], w11 * cellValues[col11])));

                        if (yAligned) {
                            int outIdx = outColBase;
                            if (cellSizeY == 8) {
                                for (int cellY = 0; cellY < cellCountY; cellY++) {
                                    final int nextCellY = Math.min(cellY + 1, cellVolSizeY - 1);
                                    final float v1 = Math.fma(w00, cellValues[col00 + nextCellY],
                                            Math.fma(w10, cellValues[col10 + nextCellY],
                                                    Math.fma(w01, cellValues[col01 + nextCellY], w11 * cellValues[col11 + nextCellY])));
                                    final float step = (v1 - v0) * cellSizeYInv;
                                    outValues[outIdx] = v0;
                                    outValues[outIdx + 1] = v0 + step;
                                    outValues[outIdx + 2] = Math.fma(step, 2.0F, v0);
                                    outValues[outIdx + 3] = Math.fma(step, 3.0F, v0);
                                    outValues[outIdx + 4] = Math.fma(step, 4.0F, v0);
                                    outValues[outIdx + 5] = Math.fma(step, 5.0F, v0);
                                    outValues[outIdx + 6] = Math.fma(step, 6.0F, v0);
                                    outValues[outIdx + 7] = Math.fma(step, 7.0F, v0);
                                    outIdx += 8;
                                    v0 = v1;
                                }
                            } else if (cellSizeY == 4) {
                                for (int cellY = 0; cellY < cellCountY; cellY++) {
                                    final int nextCellY = Math.min(cellY + 1, cellVolSizeY - 1);
                                    final float v1 = Math.fma(w00, cellValues[col00 + nextCellY],
                                            Math.fma(w10, cellValues[col10 + nextCellY],
                                                    Math.fma(w01, cellValues[col01 + nextCellY], w11 * cellValues[col11 + nextCellY])));
                                    final float step = (v1 - v0) * cellSizeYInv;
                                    outValues[outIdx] = v0;
                                    outValues[outIdx + 1] = v0 + step;
                                    outValues[outIdx + 2] = Math.fma(step, 2.0F, v0);
                                    outValues[outIdx + 3] = Math.fma(step, 3.0F, v0);
                                    outIdx += 4;
                                    v0 = v1;
                                }
                            } else {
                                for (int cellY = 0; cellY < cellCountY; cellY++) {
                                    final int nextCellY = Math.min(cellY + 1, cellVolSizeY - 1);
                                    final float v1 = Math.fma(w00, cellValues[col00 + nextCellY],
                                            Math.fma(w10, cellValues[col10 + nextCellY],
                                                    Math.fma(w01, cellValues[col01 + nextCellY], w11 * cellValues[col11 + nextCellY])));
                                    final float step = (v1 - v0) * cellSizeYInv;
                                    for (int y = 0; y < cellSizeY; y++) {
                                        outValues[outIdx + y] = Math.fma(step, y, v0);
                                    }
                                    outIdx += cellSizeY;
                                    v0 = v1;
                                }
                            }
                        } else {
                            for (int cellY = 0; cellY < cellCountY; cellY++) {
                                final int nextCellY = Math.min(cellY + 1, cellVolSizeY - 1);
                                final float v1 = Math.fma(w00, cellValues[col00 + nextCellY],
                                        Math.fma(w10, cellValues[col10 + nextCellY],
                                                Math.fma(w01, cellValues[col01 + nextCellY], w11 * cellValues[col11 + nextCellY])));
                                final int cellOutputY = baseOffsetY + cellY * cellSizeY;
                                final int y0 = Math.max(0, -cellOutputY);
                                final int y1 = Math.min(cellSizeY, outSizeY - cellOutputY) - 1;
                                if (y0 <= y1) {
                                    final float step = (v1 - v0) * cellSizeYInv;
                                    int outIdx = outColBase + cellOutputY + y0;
                                    for (int y = y0; y <= y1; y++) {
                                        outValues[outIdx++] = Math.fma(step, y, v0);
                                    }
                                }
                                v0 = v1;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Evaluates a 3D Perlin noise octave (standard or smeared Y) into {@code outValues}.
     *
     * <p>Hoists XZ bilinear interpolation and amplitude scaling once per constant-{@code floorY}
     * run so the inner Y loop executes 3 FMAs + 2 adds over flat arrays without branches, enabling
     * HotSpot C2 SuperWord SIMD vectorization.
     */
    public static void addPerlinToVolume(
            final char[] pAdj,
            final float[] outValues,
            final int sX,
            final int sY,
            final int sZ,
            final NoiseVolumeScratch scratch,
            final int yRunCount,
            final float amplitude
    ) {
        final int[] floorX = scratch.floorX;
        final float[] alphaX = scratch.alphaX;
        final float[] relX = scratch.relX;

        final float[] alphaY = scratch.alphaY;
        final float[] relY = scratch.relY;
        final int[] runStarts = scratch.yRunStarts;
        final int[] runFloors = scratch.yRunFloors;

        final int[] floorZ = scratch.floorZ;
        final float[] alphaZ = scratch.alphaZ;
        final float[] relZ = scratch.relZ;

        for (int indexX = 0; indexX < sX; ++indexX) {
            final int fX = floorX[indexX];
            final float aX = alphaX[indexX];
            final float rX = relX[indexX];
            final float rX1 = rX - 1.0F;
            final int x0 = (int) pAdj[fX & PERM_MASK];
            final int x1 = x0 >>> PERM_SHIFT;

            for (int indexZ = 0; indexZ < sZ; ++indexZ) {
                final int fZ = floorZ[indexZ];
                final float aZ = alphaZ[indexZ];
                final float rZ = relZ[indexZ];
                final float rZ1 = rZ - 1.0F;
                final int colBase = sY * (indexX + indexZ * sX);

                for (int r = 0; r < yRunCount; ++r) {
                    final int fY = runFloors[r];
                    final int xy00 = (int) pAdj[(x0 + fY) & PERM_MASK];
                    final int xy01 = xy00 >>> PERM_SHIFT;

                    final int z000 = (int) pAdj[(xy00 + fZ) & PERM_MASK];
                    final int z010 = (int) pAdj[(xy01 + fZ) & PERM_MASK];
                    final int g000 = z000 & 15;
                    final int g001 = (z000 >>> PERM_SHIFT) & 15;
                    final float d000xz = NoiseGradients.dotXz(g000, rX, rZ);
                    final float g000y = NoiseGradients.gradY(g000);
                    final float d001xz = NoiseGradients.dotXz(g001, rX, rZ1);
                    final float g001y = NoiseGradients.gradY(g001);

                    final int g010 = z010 & 15;
                    final int g011 = (z010 >>> PERM_SHIFT) & 15;
                    final float d010xz = NoiseGradients.dotXz(g010, rX, rZ);
                    final float g010y = NoiseGradients.gradY(g010);
                    final float d011xz = NoiseGradients.dotXz(g011, rX, rZ1);
                    final float g011y = NoiseGradients.gradY(g011);

                    final int xy10 = (int) pAdj[(x1 + fY) & PERM_MASK];
                    final int xy11 = xy10 >>> PERM_SHIFT;

                    final int z100 = (int) pAdj[(xy10 + fZ) & PERM_MASK];
                    final int z110 = (int) pAdj[(xy11 + fZ) & PERM_MASK];
                    final int g100 = z100 & 15;
                    final int g101 = (z100 >>> PERM_SHIFT) & 15;
                    final float d100xz = NoiseGradients.dotXz(g100, rX1, rZ);
                    final float g100y = NoiseGradients.gradY(g100);
                    final float d101xz = NoiseGradients.dotXz(g101, rX1, rZ1);
                    final float g101y = NoiseGradients.gradY(g101);

                    final int g110 = z110 & 15;
                    final int g111 = (z110 >>> PERM_SHIFT) & 15;
                    final float d110xz = NoiseGradients.dotXz(g110, rX1, rZ);
                    final float g110y = NoiseGradients.gradY(g110);
                    final float d111xz = NoiseGradients.dotXz(g111, rX1, rZ1);
                    final float g111y = NoiseGradients.gradY(g111);

                    final float base0 = amplitude * Mth.lerp2(aX, aZ, d000xz, d100xz, d001xz, d101xz);
                    final float slope0 = amplitude * Mth.lerp2(aX, aZ, g000y, g100y, g001y, g101y);
                    final float base1 = amplitude * Mth.lerp2(aX, aZ, d010xz, d110xz, d011xz, d111xz);
                    final float slope1 = amplitude * Mth.lerp2(aX, aZ, g010y, g110y, g011y, g111y);
                    final float base1Adj = base1 - slope1;

                    final int yStart = runStarts[r];
                    final int yEnd = runStarts[r + 1];
                    for (int y = yStart; y < yEnd; ++y) {
                        final float ry = relY[y];
                        final float low = Math.fma(slope0, ry, base0);
                        final float high = Math.fma(slope1, ry, base1Adj);
                        outValues[colBase + y] += Math.fma(alphaY[y], high - low, low);
                    }
                }
            }
        }
    }

    public static void add(final float[] out, final float[] rhs, final int len) {
        for (int i = 0; i < len; i++) {
            out[i] += rhs[i];
        }
    }

    public static void constAdd(final float[] out, final float c, final int len) {
        for (int i = 0; i < len; i++) {
            out[i] += c;
        }
    }

    public static void sub(final float[] out, final float[] rhs, final int len) {
        for (int i = 0; i < len; i++) {
            out[i] -= rhs[i];
        }
    }

    public static void constSub(final float[] out, final float left, final int len) {
        for (int i = 0; i < len; i++) {
            out[i] = left - out[i];
        }
    }

    public static void mul(final float[] out, final float[] rhs, final int len) {
        for (int i = 0; i < len; i++) {
            out[i] *= rhs[i];
        }
    }

    public static void constMul(final float[] out, final float c, final int len) {
        for (int i = 0; i < len; i++) {
            out[i] *= c;
        }
    }

    public static void min(final float[] out, final float[] rhs, final int len) {
        for (int i = 0; i < len; i++) {
            out[i] = Math.min(out[i], rhs[i]);
        }
    }

    public static void max(final float[] out, final float[] rhs, final int len) {
        for (int i = 0; i < len; i++) {
            out[i] = Math.max(out[i], rhs[i]);
        }
    }

    public static void constMin(final float[] out, final float c, final int len) {
        for (int i = 0; i < len; i++) {
            out[i] = Math.min(out[i], c);
        }
    }

    public static void constMax(final float[] out, final float c, final int len) {
        for (int i = 0; i < len; i++) {
            out[i] = Math.max(out[i], c);
        }
    }

    /**
     * Thread-local Structure-of-Arrays coordinate scratch for {@link #addPerlinToVolume}.
     */
    public static final class NoiseVolumeScratch {
        public int[] floorX = new int[INITIAL_XZ_CAPACITY];
        public float[] alphaX = new float[INITIAL_XZ_CAPACITY];
        public float[] relX = new float[INITIAL_XZ_CAPACITY];

        public int[] floorY = new int[INITIAL_Y_CAPACITY];
        public float[] alphaY = new float[INITIAL_Y_CAPACITY];
        public float[] relY = new float[INITIAL_Y_CAPACITY];
        public int[] yRunStarts = new int[INITIAL_Y_CAPACITY + 1];
        public int[] yRunFloors = new int[INITIAL_Y_CAPACITY];

        public int[] floorZ = new int[INITIAL_XZ_CAPACITY];
        public float[] alphaZ = new float[INITIAL_XZ_CAPACITY];
        public float[] relZ = new float[INITIAL_XZ_CAPACITY];

        void ensureCapacity(final int sX, final int sY, final int sZ) {
            if (this.floorX.length < sX) {
                this.floorX = new int[sX];
                this.alphaX = new float[sX];
                this.relX = new float[sX];
            }
            if (this.floorY.length < sY) {
                this.floorY = new int[sY];
                this.alphaY = new float[sY];
                this.relY = new float[sY];
                this.yRunStarts = new int[sY + 1];
                this.yRunFloors = new int[sY];
            }
            if (this.floorZ.length < sZ) {
                this.floorZ = new int[sZ];
                this.alphaZ = new float[sZ];
                this.relZ = new float[sZ];
            }
        }

        /**
         * Run-length encodes {@code floorY[0..sY)} into {@code yRunStarts} and {@code yRunFloors}.
         */
        public int buildYRuns(final int sY) {
            if (sY <= 0) {
                return 0;
            }
            final int[] fy = this.floorY;
            final int[] starts = this.yRunStarts;
            final int[] floors = this.yRunFloors;
            int runCount = 0;
            int curFloor = fy[0];
            starts[0] = 0;
            floors[0] = curFloor;
            for (int y = 1; y < sY; y++) {
                final int f = fy[y];
                if (f != curFloor) {
                    runCount++;
                    starts[runCount] = y;
                    floors[runCount] = f;
                    curFloor = f;
                }
            }
            runCount++;
            starts[runCount] = sY;
            return runCount;
        }
    }
}
