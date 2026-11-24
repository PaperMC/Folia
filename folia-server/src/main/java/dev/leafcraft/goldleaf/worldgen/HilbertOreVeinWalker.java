package dev.leafcraft.goldleaf.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.OreFeature;

/**
 * Optional {@code O(size)} 3D Hilbert-curve ore-vein placer ({@code worldgen.hilbert-ore-veins}).
 *
 * <p>Replaces vanilla's {@code O(size^2 + BBox^3)} multi-sphere subsumption and 3D bounding-box
 * rasterization with a zero-allocation contiguous walk along a precomputed order-3 ({@code 8x8x8},
 * 512-cell) 3D Hilbert space-filling curve randomized across all 48 cube symmetries and random
 * starting offsets. Every consecutive step is strictly face-adjacent ({@code |dx|+|dy|+|dz| == 1})
 * and visits unique coordinates, eliminating {@code BitSet} deduplication while preserving
 * natural veiny blobs and vanilla-calibrated block counts.</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017
 * @trace S-004, S-005
 */
public final class HilbertOreVeinWalker {

    public static final int CURVE_LENGTH = 512;

    /** Packed order-3 3D Hilbert curve coordinates: {@code (x & 7) | ((y & 7) << 3) | ((z & 7) << 6)}. */
    private static final short[] HILBERT_3D_LUT = buildOrder3HilbertLut();

    private HilbertOreVeinWalker() {
    }

    /** Returns the packed LUT entry at {@code index} ({@code 0..511}) for testing and verification. */
    static short packedPoint(final int index) {
        return HILBERT_3D_LUT[index];
    }

    /**
     * Calibrates the Hilbert walk length to match vanilla {@link OreFeature} average block yield
     * for a given {@code size} ({@code 1..64}), with slight organic variance.
     */
    public static int computeBlockCount(final int size, final RandomSource random) {
        if (size <= 1) {
            return 1;
        }
        final int base = size <= 16
            ? size
            : size + ((size - 16) * (size - 16)) / 18;
        final int spread = Math.max(1, base >>> 3);
        final int count = base - (spread >>> 1) + random.nextInt(spread + 1);
        return Math.min(CURVE_LENGTH, Math.max(1, count));
    }

    /**
     * Places one veiny ore blob by walking a contiguous segment of the 3D Hilbert curve anchored
     * at {@code (ox, oy, oz)}.
     */
    public static void placeVein(
        final int ox,
        final int oy,
        final int oz,
        final int size,
        final float discardChance,
        final GpuOrePlacementEngine.CompiledOreTargets targets,
        final RandomSource random,
        final ChunkAccess[] regionChunks,
        final int regionMinX,
        final int regionMinZ,
        final int minBuildY,
        final int maxBuildY,
        final BlockPos.MutableBlockPos scratchPos
    ) {
        // Fast center-column heightmap reject (matches OreFeature's above-terrain early out)
        final int crx = ox - regionMinX;
        final int crz = oz - regionMinZ;
        if ((crx | crz) >= 0 && crx < 48 && crz < 48) {
            final int surfY = regionChunks[(crz >> 4) * 3 + (crx >> 4)]
                .getHeight(Heightmap.Types.OCEAN_FLOOR_WG, ox & 15, oz & 15);
            if (oy - 4 > surfY) {
                return;
            }
        }

        final int count = computeBlockCount(size, random);
        final int orient = random.nextInt(48);
        final int perm = orient >>> 3;
        final int flipX = (orient & 1) != 0 ? 7 : 0;
        final int flipY = (orient & 2) != 0 ? 7 : 0;
        final int flipZ = (orient & 4) != 0 ? 7 : 0;
        final int startIdx = count < CURVE_LENGTH ? random.nextInt(CURVE_LENGTH - count + 1) : 0;

        final int p0 = HILBERT_3D_LUT[startIdx] & 0xFFFF;
        final int rx0 = p0 & 7;
        final int ry0 = (p0 >>> 3) & 7;
        final int rz0 = (p0 >>> 6) & 7;
        final int baseX = ox - transformCoord(rx0, ry0, rz0, perm, 0, flipX);
        final int baseY = oy - transformCoord(rx0, ry0, rz0, perm, 1, flipY);
        final int baseZ = oz - transformCoord(rx0, ry0, rz0, perm, 2, flipZ);

        final int minSectionY = minBuildY >> 4;
        final int sectionCount = regionChunks[4].getSections().length;
        final boolean hasDiscard = discardChance > 0.0F;
        final boolean alwaysBuried = discardChance >= 1.0F;

        int cachedChunkIdx = -1;
        int cachedSecIdx = -1;
        LevelChunkSection cachedSection = null;

        final int endIdx = startIdx + count;
        for (int i = startIdx; i < endIdx; i++) {
            final int p = HILBERT_3D_LUT[i] & 0xFFFF;
            final int hx = p & 7;
            final int hy = (p >>> 3) & 7;
            final int hz = (p >>> 6) & 7;
            final int wx = baseX + transformCoord(hx, hy, hz, perm, 0, flipX);
            final int wy = baseY + transformCoord(hx, hy, hz, perm, 1, flipY);
            final int wz = baseZ + transformCoord(hx, hy, hz, perm, 2, flipZ);

            if (wy < minBuildY || wy > maxBuildY) {
                continue;
            }
            final int rx = wx - regionMinX;
            final int rz = wz - regionMinZ;
            if ((rx | rz) < 0 || rx >= 48 || rz >= 48) {
                continue;
            }
            final int chunkIdx = (rz >> 4) * 3 + (rx >> 4);
            final int secIdx = (wy >> 4) - minSectionY;
            if (secIdx < 0 || secIdx >= sectionCount) {
                continue;
            }
            if (chunkIdx != cachedChunkIdx || secIdx != cachedSecIdx) {
                cachedChunkIdx = chunkIdx;
                cachedSecIdx = secIdx;
                cachedSection = regionChunks[chunkIdx].getSection(secIdx);
            }
            if (cachedSection == null || cachedSection.hasOnlyAir()) {
                continue;
            }
            final int lx = rx & 15;
            final int ly = wy & 15;
            final int lz = rz & 15;
            final BlockState current = cachedSection.getBlockState(lx, ly, lz);
            if (current.isAir()) {
                continue;
            }
            final BlockState replacement = targets.resolve(current, wy, wx, wz, scratchPos);
            if (replacement == null) {
                continue;
            }
            if (hasDiscard && (alwaysBuried || random.nextFloat() < discardChance)) {
                if (GpuOrePlacementEngine.isAdjacentToAirFast(regionChunks, cachedSection, chunkIdx, secIdx, sectionCount, rx, lx, ly, rz, lz)) {
                    continue;
                }
            }
            if (targets.allTargetsSolidNonTicking
                && (current == targets.stoneState || current == targets.deepslateState || GpuOrePlacementEngine.isSolidNonTicking(current))) {
                cachedSection.paper$setSolidNonTickingBlockState(lx, ly, lz, replacement);
            } else {
                cachedSection.setBlockState(lx, ly, lz, replacement, false);
            }
        }
    }

    private static int transformCoord(
        final int x,
        final int y,
        final int z,
        final int perm,
        final int axis,
        final int flip
    ) {
        final int raw = switch (perm) {
            case 0 -> axis == 0 ? x : (axis == 1 ? y : z);
            case 1 -> axis == 0 ? x : (axis == 1 ? z : y);
            case 2 -> axis == 0 ? y : (axis == 1 ? x : z);
            case 3 -> axis == 0 ? y : (axis == 1 ? z : x);
            case 4 -> axis == 0 ? z : (axis == 1 ? x : y);
            default -> axis == 0 ? z : (axis == 1 ? y : x);
        };
        return flip == 0 ? raw : (7 - raw);
    }

    private static short[] buildOrder3HilbertLut() {
        final short[] out = new short[CURVE_LENGTH];
        final int[] cursor = new int[1];
        generateHilbert3d(out, cursor, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 8);
        return out;
    }

    private static void generateHilbert3d(
        final short[] out,
        final int[] cursor,
        final int ox, final int oy, final int oz,
        final int ax, final int ay, final int az,
        final int bx, final int by, final int bz,
        final int cx, final int cy, final int cz,
        final int size
    ) {
        if (size == 1) {
            out[cursor[0]++] = (short) ((ox & 7) | ((oy & 7) << 3) | ((oz & 7) << 6));
            return;
        }
        final int h = size >>> 1;
        final int s = h - 1;
        final int h2 = size - 1;
        // 0: (0,0,0) enter (0,0,0), exit +s*C
        generateHilbert3d(out, cursor, ox, oy, oz, cx, cy, cz, bx, by, bz, ax, ay, az, h);
        // 1: (0,0,1) enter +h*C, exit +h*C + s*B
        generateHilbert3d(out, cursor, ox + h * cx, oy + h * cy, oz + h * cz, bx, by, bz, ax, ay, az, cx, cy, cz, h);
        // 2: (0,1,1) enter +h*B + h*C, exit +h*B + h*C + s*A
        generateHilbert3d(out, cursor, ox + h * bx + h * cx, oy + h * by + h * cy, oz + h * bz + h * cz, ax, ay, az, cx, cy, cz, bx, by, bz, h);
        // 3: (0,1,0) enter +s*A + h*B + s*C, exit +s*A + h*B
        generateHilbert3d(out, cursor, ox + s * ax + h * bx + s * cx, oy + s * ay + h * by + s * cy, oz + s * az + h * bz + s * cz, -cx, -cy, -cz, -ax, -ay, -az, bx, by, bz, h);
        // 4: (1,1,0) enter +h*A + h*B, exit +h*A + h*B + s*C
        generateHilbert3d(out, cursor, ox + h * ax + h * bx, oy + h * ay + h * by, oz + h * az + h * bz, cx, cy, cz, ax, ay, az, bx, by, bz, h);
        // 5: (1,1,1) enter +h*A + h*B + h*C, exit +h2*A + h*B + h*C
        generateHilbert3d(out, cursor, ox + h * ax + h * bx + h * cx, oy + h * ay + h * by + h * cy, oz + h * az + h * bz + h * cz, ax, ay, az, cx, cy, cz, bx, by, bz, h);
        // 6: (1,0,1) enter +h2*A + s*B + h*C, exit +h2*A + h*C
        generateHilbert3d(out, cursor, ox + h2 * ax + s * bx + h * cx, oy + h2 * ay + s * by + h * cy, oz + h2 * az + s * bz + h * cz, -bx, -by, -bz, -ax, -ay, -az, cx, cy, cz, h);
        // 7: (1,0,0) enter +h2*A + s*C, exit +h2*A
        generateHilbert3d(out, cursor, ox + h2 * ax + s * cx, oy + h2 * ay + s * cy, oz + h2 * az + s * cz, -cx, -cy, -cz, -ax, -ay, -az, bx, by, bz, h);
    }
}
