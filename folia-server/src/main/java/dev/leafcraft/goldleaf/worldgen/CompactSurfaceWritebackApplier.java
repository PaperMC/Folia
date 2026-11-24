package dev.leafcraft.goldleaf.worldgen;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.storage.GoldLeafFlatBlockSectionBacking;
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
        return buildFusedSurfaceKernelSource(densityProgram, null);
    }

    /**
     * Wraps a {@link OpenClBackendEmitter.DensityVolumeProgram} with the fused column-parallel
     * {@code fill_surface_palette_batch_u4} kernel, optionally using a {@link OpenClBackendEmitter.CoarseInterpSpec}
     * to evaluate {@code InterpolatedFunction} subtrees on each section's coarse grid in
     * {@code __local} memory ({@code 32x-80x} fewer density evaluations than dense per-voxel mode).
     */
    public static String buildFusedSurfaceKernelSource(
        final OpenClBackendEmitter.DensityVolumeProgram densityProgram,
        final OpenClBackendEmitter.@org.jspecify.annotations.Nullable CoarseInterpSpec coarseSpec
    ) {
        Objects.requireNonNull(densityProgram, "densityProgram");
        final String baseSource = densityProgram.sourceCode();
        final String evalFn = detectDensityEvalFunctionName(baseSource);
        final boolean evalAcceptsSpill = functionAcceptsGlobalSpill(baseSource, evalFn);
        final boolean evalReturnsQ32 = functionReturnsLongQ32(baseSource, evalFn);
        final StringBuilder sb = new StringBuilder(baseSource.length() + 6144);
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
        sb.append("    (void)gl_lut_spill;\n");
        sb.append("    const int lx = lid & 15;\n");
        sb.append("    const int lz = (lid >> 4) & 15;\n");
        sb.append("    const int4 origin = origins[chunk_idx];\n");
        sb.append("    const int bx = origin.x + lx;\n");
        sb.append("    const int bz = origin.z + lz;\n");
        sb.append("    const int surface_depth = 3 + (int)(gl_col_hash3(bx, 0, bz) & 3u);\n");
        sb.append("    int stone_above = 0;\n");
        sb.append("    int under_water = 0;\n");
        sb.append("    __local ushort l_non_empty[256];\n");
        sb.append("    __local ushort l_fluid[256];\n");
        sb.append("    __local ushort l_mask[256];\n");
        sb.append("    __local uchar l_codes[256];\n");
        if (coarseSpec != null) {
            final int coarseChannelCount = coarseSpec.coarseFunctionNames().size();
            final int nCoarse = coarseSpec.pointsPerChannel();
            final int totalCoarseTasks = coarseChannelCount * nCoarse;
            final int cellXz = coarseSpec.cellSizeXz();
            final float invCellXz = 1.0f / (float) cellXz;
            sb.append("    __local float coarse_vals[").append(totalCoarseTasks).append("];\n");
            sb.append("    const int col_cx = lx / ").append(cellXz).append(";\n");
            sb.append("    const int col_cz = lz / ").append(cellXz).append(";\n");
            sb.append("    const float col_fx = (float)(lx - col_cx * ").append(cellXz).append(") * ").append(invCellXz).append("f;\n");
            sb.append("    const float col_fz = (float)(lz - col_cz * ").append(cellXz).append(") * ").append(invCellXz).append("f;\n");
        }
        sb.append("    const int chunk_sec_base = chunk_idx * section_count;\n");
        sb.append("    for (int s = section_count - 1; s >= 0; s--) {\n");
        sb.append("        const int sec_min_y = min_block_y + (s << 4);\n");
        sb.append("        ushort col_non_empty = 0;\n");
        sb.append("        ushort col_fluid = 0;\n");
        sb.append("        ushort col_mask = 0;\n");
        sb.append("        const int sec_word_base = (chunk_sec_base + s) << 8;\n");
        if (coarseSpec != null) {
            final int coarseChannelCount = coarseSpec.coarseFunctionNames().size();
            final int cellXz = coarseSpec.cellSizeXz();
            final int cellY = coarseSpec.cellSizeY();
            final int nx = coarseSpec.gridSizeXz();
            final int nCoarse = coarseSpec.pointsPerChannel();
            final int totalCoarseTasks = coarseChannelCount * nCoarse;
            final int cellsY = 16 / cellY;
            final float invCellY = 1.0f / (float) cellY;
            final int strideZ = nx;
            final int strideY = nx * nx;

            sb.append("        for (int task = lid; task < ").append(totalCoarseTasks).append("; task += 256) {\n");
            sb.append("            const int ch = task / ").append(nCoarse).append(";\n");
            sb.append("            const int cidx = task - ch * ").append(nCoarse).append(";\n");
            sb.append("            const int cx = cidx % ").append(nx).append(";\n");
            sb.append("            const int ctmp = cidx / ").append(nx).append(";\n");
            sb.append("            const int cz = ctmp % ").append(nx).append(";\n");
            sb.append("            const int cy = ctmp / ").append(nx).append(";\n");
            sb.append("            const int gx = origin.x + cx * ").append(cellXz).append(";\n");
            sb.append("            const int gy = sec_min_y + cy * ").append(cellY).append(";\n");
            sb.append("            const int gz = origin.z + cz * ").append(cellXz).append(";\n");
            sb.append("            float cval = 0.0f;\n");
            sb.append("            switch (ch) {\n");
            for (int k = 0; k < coarseChannelCount; k++) {
                final String fnName = coarseSpec.coarseFunctionNames().get(k);
                final boolean chSpill = functionAcceptsGlobalSpill(baseSource, fnName);
                final boolean chQ32 = functionReturnsLongQ32(baseSource, fnName);
                final String call = chSpill
                    ? fnName + "(gx, gy, gz, gl_lut_spill)"
                    : fnName + "(gx, gy, gz)";
                final String conv = chQ32 ? "q32_to_f32(" + call + ")" : "(float)(" + call + ")";
                sb.append("                case ").append(k).append(": cval = ").append(conv).append("; break;\n");
            }
            sb.append("                default: break;\n");
            sb.append("            }\n");
            sb.append("            coarse_vals[task] = cval;\n");
            sb.append("        }\n");
            sb.append("        barrier(CLK_LOCAL_MEM_FENCE);\n");
            sb.append("        for (int cy = ").append(cellsY - 1).append("; cy >= 0; cy--) {\n");
            sb.append("            const int cell_base = (cy * ").append(nx).append(" + col_cz) * ").append(nx).append(" + col_cx;\n");
            for (int k = 0; k < coarseChannelCount; k++) {
                final int chOff = k * nCoarse;
                sb.append("            const int cb_").append(k).append(" = ").append(chOff).append(" + cell_base;\n");
                sb.append("            const float c000_").append(k).append(" = coarse_vals[cb_").append(k).append("];\n");
                sb.append("            const float c100_").append(k).append(" = coarse_vals[cb_").append(k).append(" + 1];\n");
                sb.append("            const float c001_").append(k).append(" = coarse_vals[cb_").append(k).append(" + ").append(strideZ).append("];\n");
                sb.append("            const float c101_").append(k).append(" = coarse_vals[cb_").append(k).append(" + ").append(strideZ + 1).append("];\n");
                sb.append("            const float c010_").append(k).append(" = coarse_vals[cb_").append(k).append(" + ").append(strideY).append("];\n");
                sb.append("            const float c110_").append(k).append(" = coarse_vals[cb_").append(k).append(" + ").append(strideY + 1).append("];\n");
                sb.append("            const float c011_").append(k).append(" = coarse_vals[cb_").append(k).append(" + ").append(strideY + strideZ).append("];\n");
                sb.append("            const float c111_").append(k).append(" = coarse_vals[cb_").append(k).append(" + ").append(strideY + strideZ + 1).append("];\n");
                sb.append("            const float cz00_").append(k).append(" = c000_").append(k).append(" + col_fz * (c001_").append(k).append(" - c000_").append(k).append(");\n");
                sb.append("            const float cz01_").append(k).append(" = c010_").append(k).append(" + col_fz * (c011_").append(k).append(" - c010_").append(k).append(");\n");
                sb.append("            const float cz10_").append(k).append(" = c100_").append(k).append(" + col_fz * (c101_").append(k).append(" - c100_").append(k).append(");\n");
                sb.append("            const float cz11_").append(k).append(" = c110_").append(k).append(" + col_fz * (c111_").append(k).append(" - c110_").append(k).append(");\n");
                sb.append("            const float cx0_").append(k).append(" = cz00_").append(k).append(" + col_fx * (cz10_").append(k).append(" - cz00_").append(k).append(");\n");
                sb.append("            const float cx1_").append(k).append(" = cz01_").append(k).append(" + col_fx * (cz11_").append(k).append(" - cz01_").append(k).append(");\n");
                sb.append("            const float step_").append(k).append(" = (cx1_").append(k).append(" - cx0_").append(k).append(") * ").append(invCellY).append("f;\n");
            }
            sb.append("            for (int sub_y = ").append(cellY - 1).append("; sub_y >= 0; sub_y--) {\n");
            sb.append("                const int ly = cy * ").append(cellY).append(" + sub_y;\n");
            sb.append("                const int by = sec_min_y + ly;\n");
            final StringBuilder evalCall = new StringBuilder(evalFn).append("(bx, by, bz");
            for (int k = 0; k < coarseChannelCount; k++) {
                sb.append("                const float gl_ch_").append(k).append(" = cx0_").append(k)
                    .append(" + step_").append(k).append(" * (float)sub_y;\n");
                evalCall.append(", gl_ch_").append(k);
            }
            if (evalAcceptsSpill) {
                evalCall.append(", gl_lut_spill");
            }
            evalCall.append(')');
            final String evalExpr = evalReturnsQ32
                ? "q32_to_f32(" + evalCall + ")"
                : "(float)(" + evalCall + ")";
            appendSurfaceVoxelBody(sb, "                ", evalExpr);
            sb.append("            }\n");
            sb.append("        }\n");
        } else {
            final String evalCall = evalAcceptsSpill
                ? evalFn + "(bx, by, bz, gl_lut_spill)"
                : evalFn + "(bx, by, bz)";
            final String evalExpr = evalReturnsQ32
                ? "q32_to_f32(" + evalCall + ")"
                : "(float)(" + evalCall + ")";
            sb.append("        for (int ly = 15; ly >= 0; ly--) {\n");
            sb.append("            const int by = sec_min_y + ly;\n");
            appendSurfaceVoxelBody(sb, "            ", evalExpr);
            sb.append("        }\n");
        }
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
        sb.append("            out_meta[chunk_sec_base + s] = (ushort4)(l_non_empty[0], l_fluid[0], l_mask[0], (ushort)0);\n");
        sb.append("        }\n");
        sb.append("        barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendSurfaceVoxelBody(final StringBuilder sb, final String ind, final String evalExpr) {
        sb.append(ind).append("uint code = 0u;\n");
        sb.append(ind).append("if (by <= min_block_y) {\n");
        sb.append(ind).append("    code = 4u;\n");
        sb.append(ind).append("} else if (by < min_block_y + 5 && (int)(gl_col_hash3(bx, by, bz) % 5u) >= (by - min_block_y)) {\n");
        sb.append(ind).append("    code = 4u;\n");
        sb.append(ind).append("} else {\n");
        sb.append(ind).append("    const float d = ").append(evalExpr).append(";\n");
        sb.append(ind).append("    if (d > 0.0f) {\n");
        sb.append(ind).append("        stone_above++;\n");
        sb.append(ind).append("        if (by <= 0) {\n");
        sb.append(ind).append("            code = 5u;\n");
        sb.append(ind).append("        } else if (by < 8 && (int)(gl_col_hash3(bx, by, bz) & 7u) >= by) {\n");
        sb.append(ind).append("            code = 5u;\n");
        sb.append(ind).append("        } else if (stone_above == 1 && !under_water && by >= sea_level) {\n");
        sb.append(ind).append("            code = 6u;\n");
        sb.append(ind).append("        } else if (stone_above <= surface_depth && by >= sea_level - 4) {\n");
        sb.append(ind).append("            code = under_water ? 10u : 7u;\n");
        sb.append(ind).append("        } else {\n");
        sb.append(ind).append("            code = 1u;\n");
        sb.append(ind).append("        }\n");
        sb.append(ind).append("    } else if (by < sea_level) {\n");
        sb.append(ind).append("        stone_above = 0;\n");
        sb.append(ind).append("        under_water = 1;\n");
        sb.append(ind).append("        code = (by < deep_fluid_y) ? 3u : 2u;\n");
        sb.append(ind).append("    } else {\n");
        sb.append(ind).append("        stone_above = 0;\n");
        sb.append(ind).append("        under_water = 0;\n");
        sb.append(ind).append("        code = 0u;\n");
        sb.append(ind).append("    }\n");
        sb.append(ind).append("}\n");
        sb.append(ind).append("col_mask |= (ushort)(1u << code);\n");
        sb.append(ind).append("if (code != 0u) col_non_empty++;\n");
        sb.append(ind).append("if (code == 2u || code == 3u) col_fluid++;\n");
        sb.append(ind).append("l_codes[lid] = (uchar)code;\n");
        sb.append(ind).append("barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append(ind).append("if (lid < 16) {\n");
        sb.append(ind).append("    const int row_base = lid << 4;\n");
        sb.append(ind).append("    ulong row = 0ul;\n");
        sb.append(ind).append("    for (int ix = 0; ix < 16; ix++) {\n");
        sb.append(ind).append("        row |= ((ulong)l_codes[row_base + ix]) << (ix << 2);\n");
        sb.append(ind).append("    }\n");
        sb.append(ind).append("    out_words[sec_word_base + (ly << 4) + lid] = row;\n");
        sb.append(ind).append("}\n");
        sb.append(ind).append("barrier(CLK_LOCAL_MEM_FENCE);\n");
    }

    private static boolean functionAcceptsGlobalSpill(final String openClSource, final String fnName) {
        final int fnIdx = openClSource.indexOf(fnName + "(");
        if (fnIdx < 0) {
            return false;
        }
        final int closeParen = openClSource.indexOf(')', fnIdx);
        if (closeParen < 0) {
            return false;
        }
        return openClSource.substring(fnIdx, closeParen).contains("gl_lut_spill");
    }

    private static boolean functionReturnsLongQ32(final String openClSource, final String fnName) {
        final int fnIdx = openClSource.indexOf(fnName + "(");
        if (fnIdx < 0) {
            return false;
        }
        final int lineStart = Math.max(0, openClSource.lastIndexOf('\n', fnIdx));
        final String header = openClSource.substring(lineStart, fnIdx);
        return header.contains("long ");
    }

    private static String detectDensityEvalFunctionName(final String openClSource) {
        if (openClSource.contains("evalDensity(")) {
            return "evalDensity";
        }
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
        // Fallback: match the helper called inside fill_section_palette_batch_u2 or evaluate_density_volume_batch_f32
        int kernIdx = openClSource.indexOf("__kernel void fill_section_palette_batch_u2");
        if (kernIdx < 0) {
            kernIdx = openClSource.indexOf("__kernel void evaluate_density_volume_batch_f32");
        }
        if (kernIdx >= 0) {
            final int dAssign = openClSource.indexOf("const float d = ", kernIdx);
            if (dAssign > kernIdx) {
                final int callStart = openClSource.indexOf("(bx, by, bz", dAssign);
                if (callStart > dAssign) {
                    int start = callStart;
                    while (start > dAssign) {
                        final char c = openClSource.charAt(start - 1);
                        if (Character.isLetterOrDigit(c) || c == '_') {
                            start--;
                        } else {
                            break;
                        }
                    }
                    if (start < callStart) {
                        return openClSource.substring(start, callStart);
                    }
                }
            }
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
        final boolean useDirectFlatColdBacking = isDirectFlatColdBackingEnabled();

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
            final boolean installed;
            if (useDirectFlatColdBacking) {
                final BlockState[] flatPalette = installPalette.toArray(BlockState[]::new);
                final GoldLeafFlatBlockSectionBacking flatBacking =
                    GoldLeafFlatBlockSectionBacking.fromPacked4BitLongs(raw4BitLongs, flatPalette, null);
                installed = section.paper$installBulkColdBacking(
                    flatBacking,
                    flatPalette,
                    (short) nonEmptyCount,
                    (short) fluidCount,
                    (short) tickingFluidCount
                );
            } else {
                installed = section.paper$installBulk4BitSection(
                    installPalette,
                    raw4BitLongs,
                    (short) nonEmptyCount,
                    (short) fluidCount,
                    (short) tickingFluidCount
                );
            }
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
