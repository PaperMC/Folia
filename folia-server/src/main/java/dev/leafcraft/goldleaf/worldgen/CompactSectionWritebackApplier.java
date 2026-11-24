package dev.leafcraft.goldleaf.worldgen;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.storage.GoldLeafFlatBlockSectionBacking;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Applies GPU-produced {@link CompactSectionWritebackBuffer} payloads directly to Minecraft
 * chunk sections and heightmaps ({@code goldleaf-ADR-005}, {@code goldleaf-ADR-017}).
 *
 * <p>Uses zero-per-voxel bulk installation on {@link LevelChunkSection}: uniform air sections
 * are skipped in O(1), uniform solid/fluid sections install a zero-byte {@code SingleValuePalette}
 * + {@code ZeroBitStorage}, mixed sections expand GPU 2-bit YZX words into 4-bit
 * {@code SimpleBitStorage} longs via 4-instruction SWAR bit-scatter using GPU-computed counts,
 * and {@code WORLD_SURFACE_WG} / {@code OCEAN_FLOOR_WG} heightmaps resolve top-down with
 * early exit once all 256 columns are settled.</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-005, goldleaf-ADR-017
 * @trace S-001, S-004
 */
public final class CompactSectionWritebackApplier {

    public record ApplyStats(
        int skippedAirSections,
        int uniformNonAirSections,
        int mixedSections,
        int writtenVoxels
    ) {
    }

    @FunctionalInterface
    public interface SectionLookup<S> {
        S getSection(int sectionOffset);
    }

    @FunctionalInterface
    public interface VoxelSink<S, B> {
        void write(S section, int lx, int ly, int lz, int blockY, B state);
    }

    @FunctionalInterface
    public interface HeightmapRecorder {
        void record(int lx, int blockY, int lz, int paletteCode);
    }

    private CompactSectionWritebackApplier() {
    }

    /**
     * Expands 16 packed 2-bit palette codes ({@code bits 0..31}) into 16 packed 4-bit palette
     * codes ({@code bits 0..63}) matching {@code SimpleBitStorage(4, 4096)} row layout in 4
     * SWAR bitwise steps.
     */
    public static long spread2To4(final int word) {
        long x = word & 0xFFFF_FFFFL;
        x = (x | (x << 16)) & 0x0000_FFFF_0000_FFFFL;
        x = (x | (x << 8)) & 0x00FF_00FF_00FF_00FFL;
        x = (x | (x << 4)) & 0x0F0F_0F0F_0F0F_0F0FL;
        x = (x | (x << 2)) & 0x3333_3333_3333_3333L;
        return x;
    }

    /**
     * Builds a 256-entry LUT mapping 4 packed 2-bit codes (1 byte) to 4 remapped 4-bit codes
     * (16 bits) for dimensions where {@code palette4} contains duplicate states (e.g., Nether
     * where {@code defaultFluid == deepFluid == LAVA}).
     */
    public static int[] buildRemapByteLut(final int[] codeMap) {
        final int[] lut = new int[256];
        for (int b = 0; b < 256; b++) {
            final int c0 = codeMap[b & 0x3] & 0xF;
            final int c1 = codeMap[(b >>> 2) & 0x3] & 0xF;
            final int c2 = codeMap[(b >>> 4) & 0x3] & 0xF;
            final int c3 = codeMap[(b >>> 6) & 0x3] & 0xF;
            lut[b] = c0 | (c1 << 4) | (c2 << 8) | (c3 << 12);
        }
        return lut;
    }

    /**
     * Remaps and expands 16 packed 2-bit codes into 16 packed 4-bit codes using a LUT built by
     * {@link #buildRemapByteLut(int[])}.
     */
    public static long remapAndSpread2To4(final int word, final int[] byteLut) {
        return (byteLut[word & 0xFF] & 0xFFFFL)
            | ((byteLut[(word >>> 8) & 0xFF] & 0xFFFFL) << 16)
            | ((byteLut[(word >>> 16) & 0xFF] & 0xFFFFL) << 32)
            | ((byteLut[(word >>> 24) & 0xFF] & 0xFFFFL) << 48);
    }

    /**
     * Counts occurrences of 2-bit code {@code code} ({@code 0..3}) within a 16-voxel packed row
     * word using SWAR bitwise masking + {@link Integer#bitCount(int)}.
     */
    public static int countCodeInWord(final int word, final int code) {
        final int mask = switch (code) {
            case 0 -> ~(word | (word >>> 1));
            case 1 -> word & ~(word >>> 1);
            case 2 -> ~word & (word >>> 1);
            case 3 -> word & (word >>> 1);
            default -> 0;
        };
        return Integer.bitCount(mask & 0x5555_5555);
    }

    /**
     * Applies chunk {@code batchIndex} from {@code buffer} into a live {@link ChunkAccess} via
     * zero-per-voxel bulk section installation, updating {@code OCEAN_FLOOR_WG} and
     * {@code WORLD_SURFACE_WG} heightmaps with top-down early exit.
     */
    public static ApplyStats applyToChunk(
        final CompactSectionWritebackBuffer buffer,
        final int batchIndex,
        final int sectionCount,
        final int minBlockY,
        final ChunkAccess chunk,
        final BlockState airState,
        final BlockState defaultBlock,
        final BlockState defaultFluid,
        final BlockState deepFluid
    ) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(airState, "airState");
        Objects.requireNonNull(defaultBlock, "defaultBlock");
        Objects.requireNonNull(defaultFluid, "defaultFluid");
        Objects.requireNonNull(deepFluid, "deepFluid");

        final Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        final Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        final int bottomSectionIndex = chunk.getSectionIndex(minBlockY);
        final BlockState[] palette4 = {airState, defaultBlock, defaultFluid, deepFluid};

        final List<BlockState> uniquePalette = new ArrayList<>(4);
        final int[] codeMap = new int[4];
        boolean identityMap = true;
        final boolean[] nonAirFlags = new boolean[4];
        final boolean[] fluidFlags = new boolean[4];
        final boolean[] tickingFluidFlags = new boolean[4];
        final boolean[] surfOpaque = new boolean[4];
        final boolean[] oceanOpaque = new boolean[4];
        int tickingFluidCodeMask = 0;

        for (int c = 0; c < 4; c++) {
            final BlockState st = palette4[c];
            int idx = -1;
            for (int j = 0; j < uniquePalette.size(); j++) {
                if (uniquePalette.get(j) == st) {
                    idx = j;
                    break;
                }
            }
            if (idx < 0) {
                idx = uniquePalette.size();
                uniquePalette.add(st);
            }
            codeMap[c] = idx;
            if (idx != c) {
                identityMap = false;
            }
            nonAirFlags[c] = !st.isAir();
            final boolean isFluid = !st.getFluidState().isEmpty();
            fluidFlags[c] = isFluid;
            final boolean isTickingFluid = isFluid && st.getFluidState().isRandomlyTicking();
            tickingFluidFlags[c] = isTickingFluid;
            if (isTickingFluid) {
                tickingFluidCodeMask |= (1 << c);
            }
            surfOpaque[c] = Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(st);
            oceanOpaque[c] = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(st);
        }

        // Omit deepFluid (code 3, e.g. randomly-ticking LAVA) from sections where codeMask bit 3 is 0
        final List<BlockState> uniquePaletteNoDeep = (identityMap && uniquePalette.size() == 4)
            ? uniquePalette.subList(0, 3)
            : uniquePalette;

        final boolean gpuCountsExact = !nonAirFlags[0] && nonAirFlags[1] && nonAirFlags[2] && nonAirFlags[3]
            && !fluidFlags[0] && !fluidFlags[1] && fluidFlags[2] && fluidFlags[3];
        final int[] remapLut = identityMap ? null : buildRemapByteLut(codeMap);
        final boolean useDirectFlatColdBacking = identityMap && isDirectFlatColdBackingEnabled();

        final int[] surfPendingRow = new int[16];
        final int[] oceanPendingRow = new int[16];
        Arrays.fill(surfPendingRow, 0xFFFF);
        Arrays.fill(oceanPendingRow, 0xFFFF);
        int surfRemaining = 256;
        int oceanRemaining = 256;

        final int chunkSectionBase = batchIndex * sectionCount;
        int skippedAir = 0;
        int uniformNonAir = 0;
        int mixed = 0;
        int writtenVoxels = 0;

        for (int s = sectionCount - 1; s >= 0; s--) {
            final int globalSecIdx = chunkSectionBase + s;
            final int uniform = buffer.uniformCode(globalSecIdx);
            if (uniform == CompactSectionWritebackBuffer.CODE_AIR) {
                skippedAir++;
                continue;
            }

            final LevelChunkSection section = chunk.getSection(bottomSectionIndex + s);
            final int sectionMinY = minBlockY + (s << 4);

            if (uniform > 0) {
                uniformNonAir++;
                final BlockState state = palette4[uniform];
                final short nonEmpty = nonAirFlags[uniform] ? (short) 4096 : (short) 0;
                final short fluid = fluidFlags[uniform] ? (short) 4096 : (short) 0;
                final short tickingFluid = tickingFluidFlags[uniform] ? (short) 4096 : (short) 0;
                if (!section.paper$installUniformBlockSection(state, nonEmpty, fluid, tickingFluid)) {
                    for (int ly = 15; ly >= 0; ly--) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                section.setBlockState(lx, ly, lz, state, false);
                            }
                        }
                    }
                }
                final int topHeight = sectionMinY + 16;
                if (surfRemaining > 0 && surfOpaque[uniform]) {
                    surfRemaining = resolveUniformHeightmap(worldSurface, surfPendingRow, surfRemaining, topHeight);
                }
                if (oceanRemaining > 0 && oceanOpaque[uniform]) {
                    oceanRemaining = resolveUniformHeightmap(oceanFloor, oceanPendingRow, oceanRemaining, topHeight);
                }
                writtenVoxels += 4096;
                continue;
            }

            mixed++;
            final int sectionCodeMask = buffer.codeMask(globalSecIdx);
            final int needTickingFluidMask = sectionCodeMask & tickingFluidCodeMask;

            final long[] raw4BitLongs = useDirectFlatColdBacking
                ? null
                : new long[CompactSectionWritebackBuffer.WORDS_PER_SECTION];
            final int[] sectionWords = (useDirectFlatColdBacking || surfRemaining > 0 || oceanRemaining > 0)
                ? new int[CompactSectionWritebackBuffer.WORDS_PER_SECTION]
                : null;

            int nonEmptyCount;
            int fluidCount;
            int tickingFluidCount = 0;
            if (gpuCountsExact) {
                nonEmptyCount = buffer.nonEmptyCount(globalSecIdx);
                fluidCount = buffer.fluidCount(globalSecIdx);
            } else {
                nonEmptyCount = 0;
                fluidCount = 0;
            }

            for (int w = 0; w < CompactSectionWritebackBuffer.WORDS_PER_SECTION; w++) {
                final int ly = w >>> 4;
                final int lz = w & 0xF;
                final int word = buffer.packedRowWord(globalSecIdx, ly, lz);
                if (sectionWords != null) {
                    sectionWords[w] = word;
                }
                if (word != 0) {
                    if (raw4BitLongs != null) {
                        raw4BitLongs[w] = identityMap ? spread2To4(word) : remapAndSpread2To4(word, remapLut);
                    }
                    if (!gpuCountsExact) {
                        for (int c = 0; c < 4; c++) {
                            if (nonAirFlags[c] || fluidFlags[c]) {
                                final int cnt = countCodeInWord(word, c);
                                if (nonAirFlags[c]) {
                                    nonEmptyCount += cnt;
                                }
                                if (fluidFlags[c]) {
                                    fluidCount += cnt;
                                }
                            }
                        }
                    }
                    if (needTickingFluidMask != 0) {
                        for (int m = needTickingFluidMask; m != 0; m &= m - 1) {
                            tickingFluidCount += countCodeInWord(word, Integer.numberOfTrailingZeros(m));
                        }
                    }
                } else if (raw4BitLongs != null && !identityMap && codeMap[0] != 0) {
                    raw4BitLongs[w] = remapAndSpread2To4(0, remapLut);
                }
            }

            final List<BlockState> sectionPalette = (sectionCodeMask & (1 << CompactSectionWritebackBuffer.CODE_DEEP_FLUID)) == 0
                ? uniquePaletteNoDeep
                : uniquePalette;
            final boolean installed;
            if (useDirectFlatColdBacking && sectionPalette.size() >= 2 && sectionWords != null) {
                final BlockState[] flatPalette = sectionPalette.toArray(BlockState[]::new);
                final GoldLeafFlatBlockSectionBacking flatBacking =
                    GoldLeafFlatBlockSectionBacking.fromPacked2BitWords(sectionWords, flatPalette, null);
                installed = section.paper$installBulkColdBacking(
                    flatBacking,
                    flatPalette,
                    (short) nonEmptyCount,
                    (short) fluidCount,
                    (short) tickingFluidCount
                );
            } else {
                installed = sectionPalette.size() >= 2
                    && raw4BitLongs != null
                    && section.paper$installBulk4BitSection(
                        sectionPalette,
                        raw4BitLongs,
                        (short) nonEmptyCount,
                        (short) fluidCount,
                        (short) tickingFluidCount
                    );
            }
            if (!installed) {
                for (int ly = 15; ly >= 0; ly--) {
                    for (int lz = 0; lz < 16; lz++) {
                        final int word = sectionWords != null
                            ? sectionWords[(ly << 4) | lz]
                            : buffer.packedRowWord(globalSecIdx, ly, lz);
                        if (word == 0) {
                            continue;
                        }
                        int rem = word;
                        for (int lx = 0; lx < 16 && rem != 0; lx++, rem >>>= 2) {
                            final int code = rem & 0x3;
                            if (code != CompactSectionWritebackBuffer.CODE_AIR) {
                                section.setBlockState(lx, ly, lz, palette4[code], false);
                            }
                        }
                    }
                }
            }

            if (sectionWords != null) {
                for (int ly = 15; ly >= 0 && (surfRemaining > 0 || oceanRemaining > 0); ly--) {
                    final int nextHeight = sectionMinY + ly + 1;
                    final int rowBase = ly << 4;
                    for (int lz = 0; lz < 16; lz++) {
                        int sp = surfPendingRow[lz];
                        int op = oceanPendingRow[lz];
                        int pending = sp | op;
                        if (pending == 0) {
                            continue;
                        }
                        final int word = sectionWords[rowBase | lz];
                        if (word == 0 && !surfOpaque[0] && !oceanOpaque[0]) {
                            continue;
                        }
                        while (pending != 0) {
                            final int lx = Integer.numberOfTrailingZeros(pending);
                            final int bit = 1 << lx;
                            final int code = (word >>> (lx << 1)) & 0x3;
                            if ((sp & bit) != 0 && surfOpaque[code]) {
                                worldSurface.paper$setHeightBulk(lx, lz, nextHeight);
                                sp &= ~bit;
                                surfRemaining--;
                            }
                            if ((op & bit) != 0 && oceanOpaque[code]) {
                                oceanFloor.paper$setHeightBulk(lx, lz, nextHeight);
                                op &= ~bit;
                                oceanRemaining--;
                            }
                            pending &= pending - 1;
                        }
                        surfPendingRow[lz] = sp;
                        oceanPendingRow[lz] = op;
                    }
                }
            }

            writtenVoxels += nonEmptyCount;
        }

        return new ApplyStats(skippedAir, uniformNonAir, mixed, writtenVoxels);
    }

    private static boolean isDirectFlatColdBackingEnabled() {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        if (cfg == null || cfg.storage == null || !cfg.storage.lcaNativeSectionBacking) {
            return false;
        }
        return !("mode-b".equalsIgnoreCase(cfg.storage.lcaMode)
            || "archival".equalsIgnoreCase(cfg.storage.lcaMode)
            || cfg.storage.lcaModeBLcd2);
    }

    private static int resolveUniformHeightmap(
        final Heightmap heightmap,
        final int[] pendingRow,
        int remaining,
        final int height
    ) {
        for (int lz = 0; lz < 16 && remaining > 0; lz++) {
            int pend = pendingRow[lz];
            if (pend == 0) {
                continue;
            }
            while (pend != 0) {
                final int lx = Integer.numberOfTrailingZeros(pend);
                heightmap.paper$setHeightBulk(lx, lz, height);
                remaining--;
                pend &= pend - 1;
            }
            pendingRow[lz] = 0;
        }
        return remaining;
    }

    /**
     * Pure, bootstrap-free section applier mirroring {@link #applyToChunk} for deterministic
     * Gate 1 parity and uniform-skip unit testing.
     */
    public static <S, B> ApplyStats applySections(
        final CompactSectionWritebackBuffer buffer,
        final int batchIndex,
        final int sectionCount,
        final int minBlockY,
        final SectionLookup<S> sectionLookup,
        final B[] palette4,
        final VoxelSink<S, B> voxelSink,
        final HeightmapRecorder heightmapRecorder
    ) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(sectionLookup, "sectionLookup");
        Objects.requireNonNull(palette4, "palette4");
        Objects.requireNonNull(voxelSink, "voxelSink");
        Objects.requireNonNull(heightmapRecorder, "heightmapRecorder");
        if (palette4.length < 4) {
            throw new IllegalArgumentException("palette4 must contain at least 4 entries");
        }

        final int chunkSectionBase = batchIndex * sectionCount;
        int skippedAir = 0;
        int uniformNonAir = 0;
        int mixed = 0;
        int writtenVoxels = 0;

        for (int s = sectionCount - 1; s >= 0; s--) {
            final int globalSecIdx = chunkSectionBase + s;
            final int uniform = buffer.uniformCode(globalSecIdx);
            if (uniform == CompactSectionWritebackBuffer.CODE_AIR) {
                skippedAir++;
                continue;
            }

            final S section = sectionLookup.getSection(s);
            final int sectionMinY = minBlockY + (s << 4);

            if (uniform > 0) {
                uniformNonAir++;
                final B state = palette4[uniform];
                for (int ly = 15; ly >= 0; ly--) {
                    final int blockY = sectionMinY + ly;
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            voxelSink.write(section, lx, ly, lz, blockY, state);
                        }
                    }
                }
                final int topBlockY = sectionMinY + 15;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        heightmapRecorder.record(lx, topBlockY, lz, uniform);
                    }
                }
                writtenVoxels += 4096;
                continue;
            }

            mixed++;
            for (int ly = 15; ly >= 0; ly--) {
                final int blockY = sectionMinY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    final int word = buffer.packedRowWord(globalSecIdx, ly, lz);
                    if (word == 0) {
                        continue;
                    }
                    int rem = word;
                    for (int lx = 0; lx < 16 && rem != 0; lx++, rem >>>= 2) {
                        final int code = rem & 0x3;
                        if (code != CompactSectionWritebackBuffer.CODE_AIR) {
                            voxelSink.write(section, lx, ly, lz, blockY, palette4[code]);
                            heightmapRecorder.record(lx, blockY, lz, code);
                            writtenVoxels++;
                        }
                    }
                }
            }
        }

        return new ApplyStats(skippedAir, uniformNonAir, mixed, writtenVoxels);
    }
}
