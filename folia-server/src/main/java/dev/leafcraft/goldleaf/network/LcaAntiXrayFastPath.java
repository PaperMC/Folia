package dev.leafcraft.goldleaf.network;

import java.util.Arrays;
import java.util.Objects;

/**
 * Layer 2 {@code .lca}-aware Anti-Xray fast-path evaluator ({@code goldleaf-ADR-009} §2.1, §3.3).
 *
 * <p>Operates directly on immutable section palette tables and {@code octreeOccupancyMask64}
 * (64 coarse {@code 4x4x4} sub-cubes per {@code 16x16x16} section) on worker threads without
 * touching live {@code ServerLevel} state ({@code S-005}):
 * <ul>
 *   <li>Skips uniform/air sections in {@code O(1)}.</li>
 *   <li>Skips sections whose palette contains zero hidden ores in engine-mode {@code 1} in
 *       {@code O(paletteSize)}.</li>
 *   <li>Skips empty {@code 4x4x4} sub-cubes via {@code octreeOccupancyMask64}.</li>
 *   <li>Bypasses redundant Anti-Xray obfuscation for occluded sub-sections when a modded client
 *       has negotiated {@code LeafAnvil-Net} progressive 3D occlusion ({@code goldleaf-ADR-005}).</li>
 *   <li>Evaluates exact 6-neighbor face exposure so breaking any adjacent opaque block immediately
 *       reveals hidden ores with zero ore-exposure leaks ({@code goldleaf-ADR-009} §3.3).</li>
 * </ul>
 * </p>
 */
public final class LcaAntiXrayFastPath {

    public static final int SECTION_BLOCKS = 4096;

    public enum SectionAction {
        /** Section is empty/uniform or has no obfuscation targets — skip full 4,096-block scan. */
        SKIP_UNIFORM_OR_NO_ORES,
        /** Client negotiated LeafAnvil-Net 3D occlusion and this section is fully occluded. */
        BYPASS_NEGOTIATED_OCCLUSION,
        /** Section requires block-level obfuscation (accelerated by octreeOccupancyMask64). */
        OBFUSCATE_WITH_OCTREE
    }

    public record ObfuscationStats(
        SectionAction action,
        int subCubesEvaluated,
        int subCubesSkipped,
        int blocksObfuscated
    ) {
    }

    private LcaAntiXrayFastPath() {
    }

    /**
     * Classifies a section before running block-level Anti-Xray work.
     *
     * @param hasOnlyAir               true if section contains only air
     * @param paletteBlockIds          section palette runtime block IDs
     * @param hiddenOreMask            boolean lookup indexed by runtime block ID (true = hidden ore)
     * @param engineMode               1 = hide hidden ores only; 2 = replace all occluded non-transparent blocks
     * @param negotiated3dOcclusion    true if client negotiated {@code LeafAnvil-Net} 3D occlusion
     * @param sectionFullyOccluded     true if section has zero exposed boundary faces
     */
    public static SectionAction classifySection(
        final boolean hasOnlyAir,
        final int[] paletteBlockIds,
        final boolean[] hiddenOreMask,
        final int engineMode,
        final boolean negotiated3dOcclusion,
        final boolean sectionFullyOccluded
    ) {
        Objects.requireNonNull(paletteBlockIds, "paletteBlockIds");
        Objects.requireNonNull(hiddenOreMask, "hiddenOreMask");
        if (hasOnlyAir || paletteBlockIds.length == 0) {
            return SectionAction.SKIP_UNIFORM_OR_NO_ORES;
        }
        if (negotiated3dOcclusion && sectionFullyOccluded) {
            return SectionAction.BYPASS_NEGOTIATED_OCCLUSION;
        }
        if (engineMode == 1) {
            boolean hasAnyOre = false;
            for (final int blockId : paletteBlockIds) {
                if (blockId >= 0 && blockId < hiddenOreMask.length && hiddenOreMask[blockId]) {
                    hasAnyOre = true;
                    break;
                }
            }
            if (!hasAnyOre) {
                return SectionAction.SKIP_UNIFORM_OR_NO_ORES;
            }
        } else if (paletteBlockIds.length == 1) {
            final int onlyId = paletteBlockIds[0];
            if (onlyId < 0 || onlyId >= hiddenOreMask.length || !hiddenOreMask[onlyId]) {
                return SectionAction.SKIP_UNIFORM_OR_NO_ORES;
            }
        }
        return SectionAction.OBFUSCATE_WITH_OCTREE;
    }

    /**
     * Computes the {@code 64}-bit {@code octreeOccupancyMask64} for a {@code 16x16x16} section
     * indexed by {@code (y << 8) | (z << 4) | x}. Bit {@code ((y >> 2) << 4) | ((z >> 2) << 2) | (x >> 2)}
     * is set iff the corresponding {@code 4x4x4} sub-cube contains at least one non-air block.
     */
    public static long computeOctreeOccupancyMask64(final int[] blockIds, final int airBlockId) {
        Objects.requireNonNull(blockIds, "blockIds");
        if (blockIds.length != SECTION_BLOCKS) {
            throw new IllegalArgumentException("Expected 4096 block IDs, got " + blockIds.length);
        }
        long mask = 0L;
        for (int idx = 0; idx < SECTION_BLOCKS; idx++) {
            if (blockIds[idx] != airBlockId) {
                final int x = idx & 15;
                final int z = (idx >>> 4) & 15;
                final int y = (idx >>> 8) & 15;
                final int subCube = ((y >>> 2) << 4) | ((z >>> 2) << 2) | (x >>> 2);
                mask |= (1L << subCube);
            }
        }
        return mask;
    }

    /**
     * Obfuscates an immutable copy of {@code sourceBlockIds} using engine-mode 1 (hide completely
     * occluded hidden ores with {@code replacementBlockId}), skipping empty {@code 4x4x4} sub-cubes
     * via {@code octreeOccupancyMask64}.
     *
     * <p>Boundary blocks (on the outer {@code x/y/z in {0, 15}} faces) are conservatively treated
     * as potentially exposed unless {@code assumeBoundaryOpaque} is {@code true}, ensuring zero
     * false-positive hiding of visible boundary ores while guaranteeing every interior occluded
     * ore is masked.</p>
     */
    public static ObfuscationStats obfuscateEngineMode1(
        final int[] sourceBlockIds,
        final int[] outputBlockIds,
        final long octreeOccupancyMask64,
        final boolean[] hiddenOreMask,
        final boolean[] opaqueMask,
        final int replacementBlockId,
        final boolean assumeBoundaryOpaque
    ) {
        Objects.requireNonNull(sourceBlockIds, "sourceBlockIds");
        Objects.requireNonNull(outputBlockIds, "outputBlockIds");
        Objects.requireNonNull(hiddenOreMask, "hiddenOreMask");
        Objects.requireNonNull(opaqueMask, "opaqueMask");
        if (sourceBlockIds.length != SECTION_BLOCKS || outputBlockIds.length != SECTION_BLOCKS) {
            throw new IllegalArgumentException("Source and output arrays must have length 4096");
        }
        System.arraycopy(sourceBlockIds, 0, outputBlockIds, 0, SECTION_BLOCKS);

        int subCubesEvaluated = 0;
        int subCubesSkipped = 0;
        int blocksObfuscated = 0;

        for (int subCube = 0; subCube < 64; subCube++) {
            if (((octreeOccupancyMask64 >>> subCube) & 1L) == 0L) {
                subCubesSkipped++;
                continue;
            }
            subCubesEvaluated++;
            final int baseX = (subCube & 3) << 2;
            final int baseZ = ((subCube >>> 2) & 3) << 2;
            final int baseY = ((subCube >>> 4) & 3) << 2;

            for (int dy = 0; dy < 4; dy++) {
                final int y = baseY + dy;
                for (int dz = 0; dz < 4; dz++) {
                    final int z = baseZ + dz;
                    final int rowOffset = (y << 8) | (z << 4);
                    for (int dx = 0; dx < 4; dx++) {
                        final int x = baseX + dx;
                        final int idx = rowOffset | x;
                        final int blockId = sourceBlockIds[idx];
                        if (blockId >= 0 && blockId < hiddenOreMask.length && hiddenOreMask[blockId]) {
                            if (isFullyOccluded(sourceBlockIds, x, y, z, opaqueMask, assumeBoundaryOpaque)) {
                                outputBlockIds[idx] = replacementBlockId;
                                blocksObfuscated++;
                            }
                        }
                    }
                }
            }
        }

        return new ObfuscationStats(
            SectionAction.OBFUSCATE_WITH_OCTREE,
            subCubesEvaluated,
            subCubesSkipped,
            blocksObfuscated
        );
    }

    /**
     * Obfuscates an immutable copy of {@code sourceBlockIds} using engine-mode 2 (replace every
     * fully-occluded non-transparent block with deterministic fake-ore / replacement IDs from
     * {@code replacementBlockIds}), skipping empty {@code 4x4x4} sub-cubes via
     * {@code octreeOccupancyMask64} ({@code goldleaf-ADR-009} §2.1).
     */
    public static ObfuscationStats obfuscateEngineMode2(
        final int[] sourceBlockIds,
        final int[] outputBlockIds,
        final long octreeOccupancyMask64,
        final boolean[] opaqueMask,
        final int[] replacementBlockIds,
        final boolean assumeBoundaryOpaque
    ) {
        Objects.requireNonNull(sourceBlockIds, "sourceBlockIds");
        Objects.requireNonNull(outputBlockIds, "outputBlockIds");
        Objects.requireNonNull(opaqueMask, "opaqueMask");
        Objects.requireNonNull(replacementBlockIds, "replacementBlockIds");
        if (sourceBlockIds.length != SECTION_BLOCKS || outputBlockIds.length != SECTION_BLOCKS) {
            throw new IllegalArgumentException("Source and output arrays must have length 4096");
        }
        if (replacementBlockIds.length == 0) {
            throw new IllegalArgumentException("replacementBlockIds must not be empty");
        }
        System.arraycopy(sourceBlockIds, 0, outputBlockIds, 0, SECTION_BLOCKS);

        int subCubesEvaluated = 0;
        int subCubesSkipped = 0;
        int blocksObfuscated = 0;
        final int replLen = replacementBlockIds.length;

        for (int subCube = 0; subCube < 64; subCube++) {
            if (((octreeOccupancyMask64 >>> subCube) & 1L) == 0L) {
                subCubesSkipped++;
                continue;
            }
            subCubesEvaluated++;
            final int baseX = (subCube & 3) << 2;
            final int baseZ = ((subCube >>> 2) & 3) << 2;
            final int baseY = ((subCube >>> 4) & 3) << 2;

            for (int dy = 0; dy < 4; dy++) {
                final int y = baseY + dy;
                for (int dz = 0; dz < 4; dz++) {
                    final int z = baseZ + dz;
                    final int rowOffset = (y << 8) | (z << 4);
                    for (int dx = 0; dx < 4; dx++) {
                        final int x = baseX + dx;
                        final int idx = rowOffset | x;
                        final int blockId = sourceBlockIds[idx];
                        if (blockId >= 0 && blockId < opaqueMask.length && opaqueMask[blockId]) {
                            if (isFullyOccluded(sourceBlockIds, x, y, z, opaqueMask, assumeBoundaryOpaque)) {
                                final int hash = (idx * 0x9E3779B9) ^ (x * 31 + y * 17 + z * 13);
                                outputBlockIds[idx] = replacementBlockIds[Math.floorMod(hash, replLen)];
                                blocksObfuscated++;
                            }
                        }
                    }
                }
            }
        }

        return new ObfuscationStats(
            SectionAction.OBFUSCATE_WITH_OCTREE,
            subCubesEvaluated,
            subCubesSkipped,
            blocksObfuscated
        );
    }

    /**
     * Evaluates whether block {@code (x, y, z)} is completely surrounded by opaque blocks on all
     * 6 cardinal faces.
     */
    public static boolean isFullyOccluded(
        final int[] blockIds,
        final int x,
        final int y,
        final int z,
        final boolean[] opaqueMask,
        final boolean assumeBoundaryOpaque
    ) {
        return isOpaqueNeighbor(blockIds, x - 1, y, z, opaqueMask, assumeBoundaryOpaque)
            && isOpaqueNeighbor(blockIds, x + 1, y, z, opaqueMask, assumeBoundaryOpaque)
            && isOpaqueNeighbor(blockIds, x, y - 1, z, opaqueMask, assumeBoundaryOpaque)
            && isOpaqueNeighbor(blockIds, x, y + 1, z, opaqueMask, assumeBoundaryOpaque)
            && isOpaqueNeighbor(blockIds, x, y, z - 1, opaqueMask, assumeBoundaryOpaque)
            && isOpaqueNeighbor(blockIds, x, y, z + 1, opaqueMask, assumeBoundaryOpaque);
    }

    /**
     * Collects the indices of adjacent hidden ores that become exposed when a block at
     * {@code (brokenX, brokenY, brokenZ)} is broken (replaced with air/transparent), guaranteeing
     * immediate client reveal updates with zero ore-exposure lag ({@code goldleaf-ADR-009} §3.3).
     */
    public static int[] findRevealedOreNeighborsOnBreak(
        final int[] blockIds,
        final int brokenX,
        final int brokenY,
        final int brokenZ,
        final boolean[] hiddenOreMask
    ) {
        Objects.requireNonNull(blockIds, "blockIds");
        Objects.requireNonNull(hiddenOreMask, "hiddenOreMask");
        final int[] scratch = new int[6];
        int count = 0;
        final int[][] offsets = {
            {-1, 0, 0}, {1, 0, 0},
            {0, -1, 0}, {0, 1, 0},
            {0, 0, -1}, {0, 0, 1}
        };
        for (final int[] d : offsets) {
            final int nx = brokenX + d[0];
            final int ny = brokenY + d[1];
            final int nz = brokenZ + d[2];
            if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16) {
                final int idx = (ny << 8) | (nz << 4) | nx;
                final int id = blockIds[idx];
                if (id >= 0 && id < hiddenOreMask.length && hiddenOreMask[id]) {
                    scratch[count++] = idx;
                }
            }
        }
        return Arrays.copyOf(scratch, count);
    }

    private static boolean isOpaqueNeighbor(
        final int[] blockIds,
        final int x,
        final int y,
        final int z,
        final boolean[] opaqueMask,
        final boolean assumeBoundaryOpaque
    ) {
        if (x < 0 || x >= 16 || y < 0 || y >= 16 || z < 0 || z >= 16) {
            return assumeBoundaryOpaque;
        }
        final int id = blockIds[(y << 8) | (z << 4) | x];
        return id >= 0 && id < opaqueMask.length && opaqueMask[id];
    }
}
