package dev.leafcraft.goldleaf.worldgen;

import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Applies GPU-produced 4-bit (16-state) fused {@code TERRAIN + SURFACE}
 * {@link CompactSurfaceWritebackBuffer} payloads directly into {@link ChunkAccess} sections via
 * zero-bit-scatter bulk {@code LongBuffer -> SimpleBitStorage(4, 4096)} installation, and wraps
 * any lowered {@link OpenClBackendEmitter.DensityVolumeProgram} with the column-parallel
 * {@code fill_surface_palette_batch_u4} OpenCL kernel ({@code goldleaf-ADR-017}).
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017
 * @trace S-001, S-004
 */
public final class CompactSurfaceWritebackApplier {

    public static final String SURFACE_KERNEL_NAME = "fill_surface_palette_batch_u4";

    private CompactSurfaceWritebackApplier() {
    }

    /**
     * Standard 16-state Overworld palette matching {@link CompactSurfaceWritebackBuffer} code IDs.
     */
    public static BlockState[] defaultOverworldPalette16() {
        return new BlockState[]{
            Blocks.AIR.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            Blocks.LAVA.defaultBlockState(),
            Blocks.BEDROCK.defaultBlockState(),
            Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.GRASS_BLOCK.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(),
            Blocks.SAND.defaultBlockState(),
            Blocks.SANDSTONE.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.CLAY.defaultBlockState(),
            Blocks.SNOW_BLOCK.defaultBlockState(),
            Blocks.PACKED_ICE.defaultBlockState(),
            Blocks.NETHERRACK.defaultBlockState(),
            Blocks.END_STONE.defaultBlockState()
        };
    }

    /**
     * Wraps a {@link OpenClBackendEmitter.DensityVolumeProgram} with the fused column-parallel
     * {@code fill_surface_palette_batch_u4} kernel (1 workgroup of 256 threads = 1 chunk, 1 thread
     * per {@code (lx, lz)} column scanning top-down in registers to fuse density + surface
     * stratification directly into 4-bit {@code SimpleBitStorage(4, 4096)} {@code ulong} rows).
     */
    public static String buildFusedSurfaceKernelSource(final OpenClBackendEmitter.DensityVolumeProgram densityProgram) {
        Objects.requireNonNull(densityProgram, "densityProgram");
        final String baseSource = densityProgram.sourceCode();
        final String evalFn = detectDensityEvalFunctionName(baseSource);
        final StringBuilder sb = new StringBuilder(baseSource.length() + 4096);
        sb.append(baseSource);
        sb.append("\n\n// GoldLeaf fused TERRAIN + SURFACE 4-bit column-parallel kernel (goldleaf-ADR-017)\n");
        sb.append("static inline uint gl_col_hash3(int x, int y, int z) {\n");
        sb.append("    uint h = (uint)(x * 374761393) ^ (uint)(y * 668265263) ^ (uint)(z * 2147483647);\n");
        sb.append("    h = (h ^ (h >> 13)) * 1274126177u;\n");
        sb.append("    return h ^ (h >> 16);\n");
        sb.append("}\n\n");
        sb.append("__kernel void ").append(SURFACE_KERNEL_NAME).append("(\n");
        sb.append("    __global ulong* out_words,\n");
        sb.append("    __global ushort4* out_meta,\n");
        sb.append("    __global const int4* origins,\n");
        sb.append("    const int batch_count,\n");
        sb.append("    const int section_count,\n");
        sb.append("    const int sea_level,\n");
        sb.append("    const int deep_fluid_y,\n");
        sb.append("    const int min_block_y,\n");
        sb.append("    __global const int* gl_lut_spill\n");
        sb.append(") {\n");
        sb.append("    const int chunk_idx = (int)get_group_id(0);\n");
        sb.append("    const int lid = (int)get_local_id(0);\n");
        sb.append("    if (chunk_idx >= batch_count) {\n");
        sb.append("        return;\n");
        sb.append("    }\n");
        sb.append("    const int lx = lid & 15;\n");
        sb.append("    const int lz = (lid >> 4) & 15;\n");
        sb.append("    const int4 origin = origins[chunk_idx];\n");
        sb.append("    const int bx = origin.x + lx;\n");
        sb.append("    const int bz = origin.z + lz;\n");
        sb.append("    const int surface_depth = 3 + (int)(gl_col_hash3(bx, 0, bz) & 3u);\n");
        sb.append("    int stone_above = 0;\n");
        sb.append("    int under_water = 0;\n");
        sb.append("    __local uint l_non_empty[256];\n");
        sb.append("    __local uint l_fluid[256];\n");
        sb.append("    __local uint l_mask[256];\n");
        sb.append("    __local ulong l_row_parts[16][16];\n");
        sb.append("    const int chunk_sec_base = chunk_idx * section_count;\n");
        sb.append("    for (int s = section_count - 1; s >= 0; s--) {\n");
        sb.append("        const int sec_min_y = min_block_y + (s << 4);\n");
        sb.append("        uint col_non_empty = 0u;\n");
        sb.append("        uint col_fluid = 0u;\n");
        sb.append("        uint col_mask = 0u;\n");
        sb.append("        const int sec_word_base = (chunk_sec_base + s) << 8;\n");
        sb.append("        for (int ly = 15; ly >= 0; ly--) {\n");
        sb.append("            const int by = sec_min_y + ly;\n");
        sb.append("            uint code = 0u;\n");
        sb.append("            if (by <= min_block_y) {\n");
        sb.append("                code = 4u;\n");
        sb.append("            } else if (by < min_block_y + 5 && (int)(gl_col_hash3(bx, by, bz) % 5u) >= (by - min_block_y)) {\n");
        sb.append("                code = 4u;\n");
        sb.append("            } else {\n");
        sb.append("                const float d = (float)").append(evalFn).append("(bx, by, bz, gl_lut_spill);\n");
        sb.append("                if (d > 0.0f) {\n");
        sb.append("                    stone_above++;\n");
        sb.append("                    if (by <= 0) {\n");
        sb.append("                        code = 5u;\n");
        sb.append("                    } else if (by < 8 && (int)(gl_col_hash3(bx, by, bz) & 7u) >= by) {\n");
        sb.append("                        code = 5u;\n");
        sb.append("                    } else if (stone_above == 1 && !under_water && by >= sea_level) {\n");
        sb.append("                        code = 6u;\n");
        sb.append("                    } else if (stone_above <= surface_depth && by >= sea_level - 4) {\n");
        sb.append("                        code = under_water ? 10u : 7u;\n");
        sb.append("                    } else {\n");
        sb.append("                        code = 1u;\n");
        sb.append("                    }\n");
        sb.append("                } else if (by < sea_level) {\n");
        sb.append("                    stone_above = 0;\n");
        sb.append("                    under_water = 1;\n");
        sb.append("                    code = (by < deep_fluid_y) ? 3u : 2u;\n");
        sb.append("                } else {\n");
        sb.append("                    stone_above = 0;\n");
        sb.append("                    under_water = 0;\n");
        sb.append("                    code = 0u;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("            col_mask |= (1u << code);\n");
        sb.append("            if (code != 0u) col_non_empty++;\n");
        sb.append("            if (code == 2u || code == 3u) col_fluid++;\n");
        sb.append("            l_row_parts[lz][lx] = ((ulong)code) << (lx << 2);\n");
        sb.append("            barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("            if (lx == 0) {\n");
        sb.append("                ulong row = 0ul;\n");
        sb.append("                for (int ix = 0; ix < 16; ix++) {\n");
        sb.append("                    row |= l_row_parts[lz][ix];\n");
        sb.append("                }\n");
        sb.append("                out_words[sec_word_base + (ly << 4) + lz] = row;\n");
        sb.append("            }\n");
        sb.append("            barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("        }\n");
        sb.append("        l_non_empty[lid] = col_non_empty;\n");
        sb.append("        l_fluid[lid] = col_fluid;\n");
        sb.append("        l_mask[lid] = col_mask;\n");
        sb.append("        barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("        for (int stride = 128; stride > 0; stride >>= 1) {\n");
        sb.append("            if (lid < stride) {\n");
        sb.append("                l_non_empty[lid] += l_non_empty[lid + stride];\n");
        sb.append("                l_fluid[lid] += l_fluid[lid + stride];\n");
        sb.append("                l_mask[lid] |= l_mask[lid + stride];\n");
        sb.append("            }\n");
        sb.append("            barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("        }\n");
        sb.append("        if (lid == 0) {\n");
        sb.append("            out_meta[chunk_sec_base + s] = (ushort4)((ushort)l_non_empty[0], (ushort)l_fluid[0], (ushort)l_mask[0], (ushort)0);\n");
        sb.append("        }\n");
        sb.append("        barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String detectDensityEvalFunctionName(final String openClSource) {
        final int idx = openClSource.indexOf("static inline");
        if (idx >= 0) {
            final int entryIdx = openClSource.indexOf("_eval(", idx);
            if (entryIdx > idx) {
                int start = entryIdx;
                while (start > 0) {
                    final char c = openClSource.charAt(start - 1);
                    if (Character.isLetterOrDigit(c) || c == '_') {
                        start--;
                    } else {
                        break;
                    }
                }
                return openClSource.substring(start, entryIdx + 5);
            }
        }
        // Fallback: match the helper called inside evaluate_density_volume_batch_f32
        final int kernIdx = openClSource.indexOf("__kernel void evaluate_density_volume_batch_f32");
        if (kernIdx >= 0) {
            final int spillArg = openClSource.indexOf(", gl_lut_spill)", kernIdx);
            if (spillArg > kernIdx) {
                final int paren = openClSource.lastIndexOf('(', spillArg);
                if (paren > kernIdx) {
                    int start = paren;
                    while (start > kernIdx) {
                        final char c = openClSource.charAt(start - 1);
                        if (Character.isLetterOrDigit(c) || c == '_') {
                            start--;
                        } else {
                            break;
                        }
                    }
                    return openClSource.substring(start, paren);
                }
            }
        }
        throw new IllegalArgumentException("Could not locate density evaluation helper in OpenCL source");
    }

    /**
     * Applies chunk {@code batchIndex} from a 4-bit {@link CompactSurfaceWritebackBuffer} into a
     * live {@link ChunkAccess} with zero bit-scatter ({@code LongBuffer -> SimpleBitStorage(4, 4096)}).
     */
    public static CompactSectionWritebackApplier.ApplyStats applyToChunk(
        final CompactSurfaceWritebackBuffer buffer,
        final int batchIndex,
        final int sectionCount,
        final int minBlockY,
        final ChunkAccess chunk,
        final BlockState[] palette16
    ) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(palette16, "palette16");
        if (palette16.length != CompactSurfaceWritebackBuffer.PALETTE_CAPACITY) {
            throw new IllegalArgumentException("palette16 must have length 16, got " + palette16.length);
        }

        final Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        final Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        final int bottomSectionIndex = chunk.getSectionIndex(minBlockY);
        final List<BlockState> fullPaletteList = Arrays.asList(palette16);
        final BlockState[] nonTickingPalette = Arrays.copyOf(palette16, 16);

        final boolean[] nonAirFlags = new boolean[16];
        final boolean[] fluidFlags = new boolean[16];
        final boolean[] tickingFluidFlags = new boolean[16];
        final boolean[] surfOpaque = new boolean[16];
        final boolean[] oceanOpaque = new boolean[16];
        int tickingFluidCodeMask = 0;
        int tickingBlockCodeMask = 0;
        for (int c = 0; c < 16; c++) {
            final BlockState st = palette16[c];
            nonAirFlags[c] = !st.isAir();
            final boolean isFluid = !st.getFluidState().isEmpty();
            fluidFlags[c] = isFluid;
            final boolean isTickingFluid = isFluid && st.getFluidState().isRandomlyTicking();
            tickingFluidFlags[c] = isTickingFluid;
            if (isTickingFluid) {
                tickingFluidCodeMask |= (1 << c);
            }
            if (st.isRandomlyTicking() || ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.isSpecialCollidingBlock(st)) {
                tickingBlockCodeMask |= (1 << c);
                nonTickingPalette[c] = palette16[0];
            }
            surfOpaque[c] = Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(st);
            oceanOpaque[c] = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(st);
        }
        final List<BlockState> nonTickingPaletteList = Arrays.asList(nonTickingPalette);

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
            if (uniform == CompactSurfaceWritebackBuffer.CODE_AIR) {
                skippedAir++;
                continue;
            }

            final LevelChunkSection section = chunk.getSection(bottomSectionIndex + s);
            final int sectionMinY = minBlockY + (s << 4);

            if (uniform > 0) {
                uniformNonAir++;
                final BlockState state = palette16[uniform];
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
            final long[] raw4BitLongs = new long[CompactSurfaceWritebackBuffer.LONGS_PER_SECTION];
            buffer.copySectionLongs(globalSecIdx, raw4BitLongs);

            final int nonEmptyCount = buffer.nonEmptyCount(globalSecIdx);
            final int fluidCount = buffer.fluidCount(globalSecIdx);
            final int mask = buffer.codeMask16(globalSecIdx);
            int tickingFluidCount = 0;
            if ((mask & tickingFluidCodeMask) != 0) {
                for (int w = 0; w < CompactSurfaceWritebackBuffer.LONGS_PER_SECTION; w++) {
                    long row = raw4BitLongs[w];
                    if (row == 0L) {
                        continue;
                    }
                    for (int lx = 0; lx < 16 && row != 0L; lx++, row >>>= 4) {
                        if (tickingFluidFlags[(int) (row & 0xFL)]) {
                            tickingFluidCount++;
                        }
                    }
                }
            }

            final List<BlockState> installPalette = (mask & tickingBlockCodeMask) == 0
                ? nonTickingPaletteList
                : fullPaletteList;
            final boolean installed = section.paper$installBulk4BitSection(
                installPalette,
                raw4BitLongs,
                (short) nonEmptyCount,
                (short) fluidCount,
                (short) tickingFluidCount
            );
            if (!installed) {
                for (int ly = 15; ly >= 0; ly--) {
                    for (int lz = 0; lz < 16; lz++) {
                        long row = raw4BitLongs[(ly << 4) | lz];
                        if (row == 0L) {
                            continue;
                        }
                        for (int lx = 0; lx < 16 && row != 0L; lx++, row >>>= 4) {
                            final int code = (int) (row & 0xFL);
                            if (code != 0) {
                                section.setBlockState(lx, ly, lz, palette16[code], false);
                            }
                        }
                    }
                }
            }

            if (surfRemaining > 0 || oceanRemaining > 0) {
                for (int ly = 15; ly >= 0 && (surfRemaining > 0 || oceanRemaining > 0); ly--) {
                    final int yPlusOne = sectionMinY + ly + 1;
                    final int rowBase = ly << 4;
                    for (int lz = 0; lz < 16; lz++) {
                        int sPending = surfPendingRow[lz];
                        int oPending = oceanPendingRow[lz];
                        if ((sPending | oPending) == 0) {
                            continue;
                        }
                        final long row = raw4BitLongs[rowBase | lz];
                        if (row == 0L) {
                            continue;
                        }
                        int active = sPending | oPending;
                        while (active != 0) {
                            final int lx = Integer.numberOfTrailingZeros(active);
                            final int bit = 1 << lx;
                            active ^= bit;
                            final int code = (int) ((row >>> (lx << 2)) & 0xFL);
                            if (code == 0) {
                                continue;
                            }
                            if ((sPending & bit) != 0 && surfOpaque[code]) {
                                worldSurface.paper$setHeightBulk(lx, lz, yPlusOne);
                                sPending ^= bit;
                                surfRemaining--;
                            }
                            if ((oPending & bit) != 0 && oceanOpaque[code]) {
                                oceanFloor.paper$setHeightBulk(lx, lz, yPlusOne);
                                oPending ^= bit;
                                oceanRemaining--;
                            }
                        }
                        surfPendingRow[lz] = sPending;
                        oceanPendingRow[lz] = oPending;
                    }
                }
            }
            writtenVoxels += nonEmptyCount;
        }

        return new CompactSectionWritebackApplier.ApplyStats(skippedAir, uniformNonAir, mixed, writtenVoxels);
    }

    private static int resolveUniformHeightmap(
        final Heightmap heightmap,
        final int[] pendingRow,
        int remaining,
        final int height
    ) {
        for (int lz = 0; lz < 16 && remaining > 0; lz++) {
            int pending = pendingRow[lz];
            while (pending != 0) {
                final int lx = Integer.numberOfTrailingZeros(pending);
                heightmap.paper$setHeightBulk(lx, lz, height);
                pending &= pending - 1;
                remaining--;
            }
            pendingRow[lz] = 0;
        }
        return remaining;
    }
}
