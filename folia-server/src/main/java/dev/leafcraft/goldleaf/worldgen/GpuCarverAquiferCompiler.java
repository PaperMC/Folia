package dev.leafcraft.goldleaf.worldgen;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter;
import io.github.dailystruggle.bytecode.transpile.ConceptOptimizerPass;
import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.FindTopSurfaceFunction;
import org.jspecify.annotations.Nullable;

/**
 * Compiles Mojang's live {@link Aquifer.Config} into a vanilla-exact OpenCL 1.2 Pass 3 kernel
 * ({@code apply_carver_aquifer_u8}) that executes in-place on {@code out_voxels} immediately after
 * {@code apply_vanilla_surface_u8} on the same GPU command queue, then performs in-place 4-bit
 * section packing.
 *
 * <p>Uses {@code INT64_Q32_FP32} (zero FP64), exact {@code Xoroshiro128++} aquifer cell positions,
 * workgroup-parallel {@code __local} 2D {@code surfaceLevel} and 3D aquifer cell caches, and
 * emits rare fluid post-processing and grass-dirt fixup events into a bounded per-chunk buffer
 * (failing closed on overflow via {@link #FLAG_CARVER_EVENT_OVERFLOW}).</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017, leafgen-ADR-008
 * @trace S-002, S-004
 */
public final class GpuCarverAquiferCompiler {

    public static final String KERNEL_NAME = "apply_carver_aquifer_u8";
    public static final String TERRAIN_AQUIFER_KERNEL_NAME = "apply_terrain_aquifer_u8";
    public static final int AQ_CELL_CACHE_INTS_PER_CHUNK = 4 + 384 * 2;
    public static final int MAX_CARVER_EVENTS = 2048;
    public static final byte FLAG_CARVER_EVENT_OVERFLOW = 0x04;
    public static final int EVENT_TYPE_FLUID_POST_PROCESS = 0;

    private GpuCarverAquiferCompiler() {
    }

    public static OpenClBackendEmitter.@Nullable DensityVolumeProgram compile(
        final NoiseGeneratorSettings settings,
        final RandomState randomState,
        final BlockState[] blockPalette,
        final OpenClBackendEmitter openClEmitter,
        final ConceptBackendEmitter.GpuExactArithmeticMode arithmeticMode,
        final String moduleBaseName
    ) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(randomState, "randomState");
        Objects.requireNonNull(blockPalette, "blockPalette");
        Objects.requireNonNull(openClEmitter, "openClEmitter");

        if (settings.aquifers().isEmpty() || blockPalette.length > 64) {
            return null;
        }
        final PositionalRandomFactory aquiferRandom = randomState.getOrCreateRandomFactory(
            Identifier.withDefaultNamespace("aquifer")
        );
        if (!(aquiferRandom instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory xorAquifer)) {
            return null;
        }

        final Aquifer.Config aqCfg = settings.aquifers().get();
        final DensityFunction.CompileContext compileCtx = randomState.paper$compileContext();
        final ConceptBackendEmitter.EmitOptions emitOptions = ConceptBackendEmitter.EmitOptions.of(
            arithmeticMode,
            ConceptBackendEmitter.BatchLayout.SECTION_PALETTE_FILL_U2
        );

        final DensityFunction unwrappedSurf = unwrapFunction(aqCfg.surfaceLevel());
        final FindTopSurfaceFunction topSurfFn = unwrappedSurf instanceof FindTopSurfaceFunction f ? f : null;

        final List<DensityFunction> rawFns = new ArrayList<>(7);
        rawFns.add(aqCfg.barrierNoise());                // 0
        rawFns.add(aqCfg.fluidLevelFloodednessNoise());  // 1
        rawFns.add(aqCfg.fluidLevelSpreadNoise());       // 2
        rawFns.add(aqCfg.lavaNoise());                   // 3
        rawFns.add(aqCfg.exclusion());                   // 4
        if (topSurfFn != null) {
            rawFns.add(topSurfFn.upperBound());          // 5
            rawFns.add(topSurfFn.density());             // 6
        } else {
            rawFns.add(aqCfg.surfaceLevel());            // 5
        }

        final List<BytecodeConceptIr.ConceptModule> modules = new ArrayList<>(rawFns.size());
        for (int i = 0; i < rawFns.size(); i++) {
            final DensityFunctionIrLowerer.LoweringResult res = DensityFunctionIrLowerer.lower(
                rawFns.get(i),
                compileCtx,
                moduleBaseName + "_aq_" + i,
                true
            );
            if (!res.isSupported() || res.module() == null) {
                org.slf4j.LoggerFactory.getLogger(GpuCarverAquiferCompiler.class).info(
                    "[GoldLeaf-OpenCL] Carver aquifer compiler skipped '{}': fn[{}] unsupported ({})",
                    moduleBaseName, i, res.unsupportedReason()
                );
                return null;
            }
            modules.add(ConceptOptimizerPass.optimize(res.module(), emitOptions));
        }

        final OpenClBackendEmitter.MultiModuleSectionProgram multi = openClEmitter.emitSectionFillWithAuxModules(
            modules.get(0),
            null,
            modules.subList(1, modules.size()),
            emitOptions
        );
        final String primarySrc = multi.primarySourceCode();
        final int kernIdx = primarySrc.indexOf("__kernel void fill_section_palette_batch_u2(");
        if (kernIdx < 0) {
            return null;
        }
        final String primaryPrefix = primarySrc.substring(0, kernIdx);
        final String aux0Call = buildSingleAuxCallExpr(primaryPrefix);
        if (aux0Call == null) {
            return null;
        }
        final List<String> allCalls = new ArrayList<>(modules.size());
        allCalls.add(aux0Call);
        allCalls.addAll(multi.auxEntryCallExprs());

        // Precompute palette bitmasks & special block IDs
        long uncarvableMask = 0L;
        long tickingFluidMask = 0L;
        long surfOpaqueMask = 0L;
        long oceanOpaqueMask = 0L;
        final var surfPred = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
        final var oceanPred = net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        int codeGrassBlock = -1;
        int codeMycelium = -1;
        int codeDirt = -1;
        for (int i = 0; i < blockPalette.length; i++) {
            final BlockState st = blockPalette[i];
            if (st.is(BlockTags.UNCARVABLE)) {
                uncarvableMask |= (1L << i);
            }
            final var fs = st.getFluidState();
            if (!fs.isEmpty() && fs.isRandomlyTicking()) {
                tickingFluidMask |= (1L << i);
            }
            if (surfPred.test(st)) {
                surfOpaqueMask |= (1L << i);
            }
            if (oceanPred.test(st)) {
                oceanOpaqueMask |= (1L << i);
            }
            if (codeGrassBlock < 0 && st.is(Blocks.GRASS_BLOCK)) {
                codeGrassBlock = i;
            } else if (codeMycelium < 0 && st.is(Blocks.MYCELIUM)) {
                codeMycelium = i;
            } else if (codeDirt < 0 && st.is(Blocks.DIRT)) {
                codeDirt = i;
            }
        }

        final String auxHelpersNonInline = multi.auxiliaryHelpersSource().replace("static inline ", "static ");
        final StringBuilder sb = new StringBuilder(primaryPrefix.length() + auxHelpersNonInline.length() + 16384);
        sb.append(primaryPrefix);
        sb.append("\n// === Auxiliary Aquifer Density Helpers ===\n");
        sb.append(auxHelpersNonInline);
        sb.append('\n');

        emitAquiferHelpers(
            sb,
            allCalls,
            topSurfFn,
            xorAquifer.goldleaf$seedLo(),
            xorAquifer.goldleaf$seedHi()
        );
        emitTerrainAquiferKernel(sb);
        emitCarverAquiferKernel(
            sb,
            uncarvableMask,
            tickingFluidMask,
            surfOpaqueMask,
            oceanOpaqueMask,
            codeGrassBlock,
            codeMycelium,
            codeDirt
        );

        final String source = sb.toString();
        final int[] spill = multi.spilledLutWords();
        return new OpenClBackendEmitter.DensityVolumeProgram(
            KERNEL_NAME,
            source,
            sha256(arithmeticMode.name() + "\n" + source, spill),
            spill,
            multi.usedConstantBytes(),
            multi.spilledLutCount(),
            multi.deduplicatedLutCount()
        );
    }

    private static DensityFunction unwrapFunction(DensityFunction fn) {
        while (true) {
            if (fn instanceof DensityFunctions.HolderHolder hh) {
                fn = hh.function().value();
            } else if (fn instanceof CacheFunction cf) {
                fn = cf.input();
            } else {
                return fn;
            }
        }
    }

    private static void emitAquiferHelpers(
        final StringBuilder sb,
        final List<String> allCalls,
        final @Nullable FindTopSurfaceFunction topSurfFn,
        final long aqSeedLo,
        final long aqSeedHi
    ) {
        sb.append("#define AQ_WAY_BELOW_MIN_Y ").append(DimensionType.WAY_BELOW_MIN_Y).append('\n');
        sb.append("#define AQ_MAX_EVENTS ").append(MAX_CARVER_EVENTS).append('\n');
        sb.append("#define AQ_FLAG_OVERFLOW ").append((int) FLAG_CARVER_EVENT_OVERFLOW).append('\n');
        sb.append("#define AQ_CACHE_STRIDE ").append(AQ_CELL_CACHE_INTS_PER_CHUNK).append('\n');
        sb.append("#define AQ_SEED_LO ").append(toUnsignedHex(aqSeedLo)).append('\n');
        sb.append("#define AQ_SEED_HI ").append(toUnsignedHex(aqSeedHi)).append('\n');
        sb.append("#define AQ_FLOWING_SIM (-0.76f)\n\n");

        sb.append("static inline ulong aq_rotl64(const ulong x, const int k) {\n");
        sb.append("    return (x << k) | (x >> (64 - k));\n");
        sb.append("}\n");
        sb.append("static inline ulong aq_xoro_next_long(ulong* s0, ulong* s1) {\n");
        sb.append("    const ulong lo = *s0;\n");
        sb.append("    ulong hi = *s1;\n");
        sb.append("    const ulong res = aq_rotl64(lo + hi, 17) + lo;\n");
        sb.append("    hi ^= lo;\n");
        sb.append("    *s0 = aq_rotl64(lo, 49) ^ hi ^ (hi << 21);\n");
        sb.append("    *s1 = aq_rotl64(hi, 28);\n");
        sb.append("    return res;\n");
        sb.append("}\n");
        sb.append("static inline int aq_xoro_next_int(ulong* s0, ulong* s1, const uint bound) {\n");
        sb.append("    ulong rb = (ulong)((uint)aq_xoro_next_long(s0, s1));\n");
        sb.append("    ulong mult = rb * (ulong)bound;\n");
        sb.append("    ulong frac = mult & 0xFFFFFFFFUL;\n");
        sb.append("    if (frac < (ulong)bound) {\n");
        sb.append("        const uint thresh = (uint)((~bound + 1u) % bound);\n");
        sb.append("        while (frac < (ulong)thresh) {\n");
        sb.append("            rb = (ulong)((uint)aq_xoro_next_long(s0, s1));\n");
        sb.append("            mult = rb * (ulong)bound;\n");
        sb.append("            frac = mult & 0xFFFFFFFFUL;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    return (int)(mult >> 32);\n");
        sb.append("}\n");
        sb.append("static inline long aq_mth_get_seed(const int x, const int y, const int z) {\n");
        sb.append("    long i = (long)(x * 3129871) ^ (long)z * 116129781L ^ (long)y;\n");
        sb.append("    i = i * i * 42317861L + i * 11L;\n");
        sb.append("    return i >> 16;\n");
        sb.append("}\n");
        sb.append("static inline int aq_floor_div(const int a, const int b) {\n");
        sb.append("    const int q = a / b;\n");
        sb.append("    const int r = a % b;\n");
        sb.append("    return (r != 0 && a < 0) ? (q - 1) : q;\n");
        sb.append("}\n");
        sb.append("static inline float aq_clamped_map(const float val, const float fromMin, const float fromMax, const float toMin, const float toMax) {\n");
        sb.append("    const float t = clamp((val - fromMin) / (fromMax - fromMin), 0.0f, 1.0f);\n");
        sb.append("    return toMin + t * (toMax - toMin);\n");
        sb.append("}\n");
        sb.append("static inline float aq_map(const float val, const float fromMin, const float fromMax, const float toMin, const float toMax) {\n");
        sb.append("    const float t = (val - fromMin) / (fromMax - fromMin);\n");
        sb.append("    return toMin + t * (toMax - toMin);\n");
        sb.append("}\n");
        sb.append("static inline uchar aq_fluid_at(const int lvl, const uchar ftype, const int by) {\n");
        sb.append("    return (by < lvl) ? ftype : (uchar)0;\n");
        sb.append("}\n");
        sb.append("static inline void aq_global_fluid(const int by, const int sea_level, const int deep_fluid_y, int* out_lvl, uchar* out_type) {\n");
        sb.append("    if (by < min(deep_fluid_y, sea_level)) {\n");
        sb.append("        *out_lvl = deep_fluid_y;\n");
        sb.append("        *out_type = (uchar)3;\n");
        sb.append("    } else {\n");
        sb.append("        *out_lvl = sea_level;\n");
        sb.append("        *out_type = (uchar)2;\n");
        sb.append("    }\n");
        sb.append("}\n\n");

        // Individual density wrapper functions
        sb.append("static float aq_eval_barrier(const int bx, const int by, const int bz, __global const int* gl_lut_spill) {\n");
        sb.append("    return ").append(allCalls.get(0)).append(";\n}\n");
        sb.append("static float aq_eval_floodedness(const int bx, const int by, const int bz, __global const int* gl_lut_spill) {\n");
        sb.append("    return ").append(allCalls.get(1)).append(";\n}\n");
        sb.append("static float aq_eval_spread(const int bx, const int by, const int bz, __global const int* gl_lut_spill) {\n");
        sb.append("    return ").append(allCalls.get(2)).append(";\n}\n");
        sb.append("static float aq_eval_lava(const int bx, const int by, const int bz, __global const int* gl_lut_spill) {\n");
        sb.append("    return ").append(allCalls.get(3)).append(";\n}\n");
        sb.append("static float aq_eval_exclusion(const int bx, const int by, const int bz, __global const int* gl_lut_spill) {\n");
        sb.append("    return ").append(allCalls.get(4)).append(";\n}\n");

        // Surface level helper (FindTopSurfaceFunction or direct DensityFunction)
        sb.append("static int aq_eval_surface_level(const int bx, const int bz, __global const int* gl_lut_spill) {\n");
        if (topSurfFn != null) {
            final int lowerBound = topSurfFn.lowerBound();
            final int cellHeight = topSurfFn.cellHeight();
            sb.append("    const int by = 0;\n");
            sb.append("    const float ub = ").append(allCalls.get(5)).append(";\n");
            sb.append("    const int top_y = (int)floor(ub / ").append((float) cellHeight).append("f) * ").append(cellHeight).append(";\n");
            sb.append("    if (top_y <= ").append(lowerBound).append(") return ").append(lowerBound).append(";\n");
            sb.append("    for (int py = top_y; py >= ").append(lowerBound).append("; py -= ").append(cellHeight).append(") {\n");
            sb.append("        const int by = py;\n");
            sb.append("        if (").append(allCalls.get(6)).append(" > 0.0f) return py;\n");
            sb.append("    }\n");
            sb.append("    return ").append(lowerBound).append(";\n");
        } else {
            sb.append("    const int by = 0;\n");
            sb.append("    return (int)floor(").append(allCalls.get(5)).append(");\n");
        }
        sb.append("}\n\n");

        // Exact Aquifer.NoiseBasedAquifer#calculatePressure
        sb.append("static float aq_calc_pressure(\n");
        sb.append("    const int bx, const int by, const int bz,\n");
        sb.append("    const int lvl1, const uchar typ1, const int lvl2, const uchar typ2,\n");
        sb.append("    int* has_barrier, float* barrier_cache,\n");
        sb.append("    __global const int* gl_lut_spill)\n");
        sb.append("{\n");
        sb.append("    const uchar t1 = aq_fluid_at(lvl1, typ1, by);\n");
        sb.append("    const uchar t2 = aq_fluid_at(lvl2, typ2, by);\n");
        sb.append("    if ((t1 == 3 && t2 == 2) || (t1 == 2 && t2 == 3)) {\n");
        sb.append("        return 2.0f;\n");
        sb.append("    }\n");
        sb.append("    const int diff = abs(lvl1 - lvl2);\n");
        sb.append("    if (diff == 0) return 0.0f;\n");
        sb.append("    const float avg_y = 0.5f * (float)(lvl1 + lvl2);\n");
        sb.append("    const float above = (float)by + 0.5f - avg_y;\n");
        sb.append("    const float base = (float)diff * 0.5f;\n");
        sb.append("    const float dist = base - fabs(above);\n");
        sb.append("    float grad;\n");
        sb.append("    if (above > 0.0f) {\n");
        sb.append("        grad = (dist > 0.0f) ? (dist / 1.5f) : (dist / 2.5f);\n");
        sb.append("    } else {\n");
        sb.append("        const float cp = 3.0f + dist;\n");
        sb.append("        grad = (cp > 0.0f) ? (cp / 3.0f) : (cp / 10.0f);\n");
        sb.append("    }\n");
        sb.append("    float nv = 0.0f;\n");
        sb.append("    if (grad >= -2.0f && grad <= 2.0f) {\n");
        sb.append("        if (*has_barrier == 0) {\n");
        sb.append("            *barrier_cache = aq_eval_barrier(bx, by, bz, gl_lut_spill);\n");
        sb.append("            *has_barrier = 1;\n");
        sb.append("        }\n");
        sb.append("        nv = *barrier_cache;\n");
        sb.append("    }\n");
        sb.append("    return 2.0f * (nv + grad);\n");
        sb.append("}\n\n");
    }

    private static void emitTerrainAquiferKernel(final StringBuilder sb) {
        sb.append("__constant int AQ_SURF_OFF_X[13] = {0, -2, -1, 0, 1, -3, -2, -1, 1, -2, -1, 0, 1};\n");
        sb.append("__constant int AQ_SURF_OFF_Z[13] = {0, -1, -1, -1, -1, 0, 0, 0, 0, 1, 1, 1, 1};\n\n");

        sb.append("__kernel void ").append(TERRAIN_AQUIFER_KERNEL_NAME).append("(\n");
        sb.append("    __global uchar* restrict out_voxels,\n");
        sb.append("    __global const int4* restrict batch_origins,\n");
        sb.append("    __global uint* restrict out_carver_events,\n");
        sb.append("    __global int* restrict out_carver_counts,\n");
        sb.append("    __global int* restrict out_aq_cache,\n");
        sb.append("    const int batch_count,\n");
        sb.append("    const int section_count,\n");
        sb.append("    const int sea_level,\n");
        sb.append("    const int deep_fluid_y,\n");
        sb.append("    const int min_block_y,\n");
        sb.append("    __global const int* restrict gl_lut_spill)\n");
        sb.append("{\n");
        sb.append("    const int batch_idx = (int)get_group_id(0);\n");
        sb.append("    const int tid = (int)get_local_id(0);\n");
        sb.append("    if (batch_idx >= batch_count) return;\n");
        sb.append("    const int lx = tid & 15;\n");
        sb.append("    const int lz = tid >> 4;\n");
        sb.append("    const int col_idx = tid;\n");
        sb.append("    const int chunk_base_sec = batch_idx * section_count;\n");
        sb.append("    const int4 orig = batch_origins[batch_idx];\n");
        sb.append("    const int total_height = section_count * 16;\n");
        sb.append("    const int max_block_y = min_block_y + total_height - 1;\n");
        sb.append("    const int min_gx = (orig.x - 5) >> 4;\n");
        sb.append("    const int max_gx = ((orig.x + 10) >> 4) + 1;\n");
        sb.append("    const int gs_x = max_gx - min_gx + 1;\n");
        sb.append("    const int min_gz = (orig.z - 5) >> 4;\n");
        sb.append("    const int max_gz = ((orig.z + 10) >> 4) + 1;\n");
        sb.append("    const int gs_z = max_gz - min_gz + 1;\n");
        sb.append("    const int min_gy = aq_floor_div(min_block_y + 1, 12) - 1;\n");
        sb.append("    const int max_gy = aq_floor_div(max_block_y + 1, 12) + 1;\n");
        sb.append("    const int gs_y = max_gy - min_gy + 1;\n");
        sb.append("    const int total_cells = gs_x * gs_y * gs_z;\n");
        sb.append("    __global int* chunk_cache = out_aq_cache + batch_idx * AQ_CACHE_STRIDE;\n");
        sb.append("    if (total_cells > 384) {\n");
        sb.append("        if (tid == 0) { chunk_cache[0] = min_block_y - 1; chunk_cache[1] = -1; }\n");
        sb.append("        for (int rel_y = 0; rel_y < total_height; rel_y++) {\n");
        sb.append("            const int v_off = (chunk_base_sec + (rel_y >> 4)) * 4096 + (((rel_y & 15) << 8) | col_idx);\n");
        sb.append("            if (out_voxels[v_off] >= 128) {\n");
        sb.append("                const int by = min_block_y + rel_y;\n");
        sb.append("                out_voxels[v_off] = (by < sea_level) ? (uchar)2 : (uchar)0;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return;\n");
        sb.append("    }\n");
        sb.append("    __local short l_surf[513];\n");
        sb.append("    __local int l_max_surf[256];\n");
        sb.append("    __local short l_aq_x[384];\n");
        sb.append("    __local short l_aq_y[384];\n");
        sb.append("    __local short l_aq_z[384];\n");
        sb.append("    __local int l_aq_lvl[384];\n");
        sb.append("    __local uchar l_aq_type[384];\n\n");

        sb.append("    const int surf_min_qx = (min_gx << 2) - 12;\n");
        sb.append("    const int surf_min_qz = (min_gz << 2) - 4;\n");
        sb.append("    for (int idx = tid; idx < 513; idx += 256) {\n");
        sb.append("        const int sx = idx % 27;\n");
        sb.append("        const int sz = idx / 27;\n");
        sb.append("        const int qx = surf_min_qx + sx;\n");
        sb.append("        const int qz = surf_min_qz + sz;\n");
        sb.append("        l_surf[idx] = (short)aq_eval_surface_level(qx << 2, qz << 2, gl_lut_spill);\n");
        sb.append("    }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n\n");

        sb.append("    int my_max = -2147483647;\n");
        sb.append("    if (tid < 121) {\n");
        sb.append("        const int ix = 12 + (tid % 11);\n");
        sb.append("        const int iz = 4 + (tid / 11);\n");
        sb.append("        my_max = (int)l_surf[iz * 27 + ix];\n");
        sb.append("    }\n");
        sb.append("    l_max_surf[tid] = my_max;\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("    for (int stride = 128; stride > 0; stride >>= 1) {\n");
        sb.append("        if (tid < stride) {\n");
        sb.append("            l_max_surf[tid] = max(l_max_surf[tid], l_max_surf[tid + stride]);\n");
        sb.append("        }\n");
        sb.append("        barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("    }\n");
        sb.append("    const int max_adj_surf = l_max_surf[0] + 8;\n");
        sb.append("    const int skip_gy = aq_floor_div(max_adj_surf + 12, 12) + 1;\n");
        sb.append("    const int skip_above_y = skip_gy * 12 + 10;\n");
        sb.append("    if (tid == 0) {\n");
        sb.append("        chunk_cache[0] = skip_above_y;\n");
        sb.append("        chunk_cache[1] = total_cells;\n");
        sb.append("    }\n\n");

        sb.append("    for (int cidx = tid; cidx < total_cells; cidx += 256) {\n");
        sb.append("        const int cx = cidx % gs_x;\n");
        sb.append("        const int cz = (cidx / gs_x) % gs_z;\n");
        sb.append("        const int cy = cidx / (gs_x * gs_z);\n");
        sb.append("        const int gx = min_gx + cx;\n");
        sb.append("        const int gy = min_gy + cy;\n");
        sb.append("        const int gz = min_gz + cz;\n");
        sb.append("        ulong s0 = (ulong)(aq_mth_get_seed(gx, gy, gz) ^ (long)AQ_SEED_LO);\n");
        sb.append("        ulong s1 = AQ_SEED_HI;\n");
        sb.append("        const int loc_x = (gx << 4) + aq_xoro_next_int(&s0, &s1, 10u);\n");
        sb.append("        const int loc_y = gy * 12 + aq_xoro_next_int(&s0, &s1, 9u);\n");
        sb.append("        const int loc_z = (gz << 4) + aq_xoro_next_int(&s0, &s1, 10u);\n");
        sb.append("        const int rel_x = loc_x - orig.x;\n");
        sb.append("        const int rel_z = loc_z - orig.z;\n");
        sb.append("        l_aq_x[cidx] = (short)rel_x;\n");
        sb.append("        l_aq_y[cidx] = (short)loc_y;\n");
        sb.append("        l_aq_z[cidx] = (short)rel_z;\n");
        sb.append("        int g_lvl; uchar g_type;\n");
        sb.append("        aq_global_fluid(loc_y, sea_level, deep_fluid_y, &g_lvl, &g_type);\n");
        sb.append("        int res_lvl = g_lvl;\n");
        sb.append("        uchar res_type = g_type;\n");
        sb.append("        if (loc_y <= skip_above_y + 24) {\n");
        sb.append("            int lowest_surf = 2147483647;\n");
        sb.append("            const int top_cell = loc_y + 12;\n");
        sb.append("            const int bot_cell = loc_y - 12;\n");
        sb.append("            int center_under_global = 0;\n");
        sb.append("            int early_ret = 0;\n");
        sb.append("            for (int oi = 0; oi < 13; oi++) {\n");
        sb.append("                const int samp_x = loc_x + (AQ_SURF_OFF_X[oi] << 4);\n");
        sb.append("                const int samp_z = loc_z + (AQ_SURF_OFF_Z[oi] << 4);\n");
        sb.append("                const int sqx = (samp_x >> 2) - surf_min_qx;\n");
        sb.append("                const int sqz = (samp_z >> 2) - surf_min_qz;\n");
        sb.append("                const int s_lvl = (int)l_surf[sqz * 27 + sqx];\n");
        sb.append("                const int adj_s = s_lvl + 8;\n");
        sb.append("                if (oi == 0 && bot_cell > adj_s) {\n");
        sb.append("                    early_ret = 1;\n");
        sb.append("                    break;\n");
        sb.append("                }\n");
        sb.append("                const int pokes = (top_cell > adj_s) ? 1 : 0;\n");
        sb.append("                if (pokes != 0 || oi == 0) {\n");
        sb.append("                    int gs_lvl; uchar gs_type;\n");
        sb.append("                    aq_global_fluid(adj_s, sea_level, deep_fluid_y, &gs_lvl, &gs_type);\n");
        sb.append("                    if (aq_fluid_at(gs_lvl, gs_type, adj_s) != 0) {\n");
        sb.append("                        if (oi == 0) center_under_global = 1;\n");
        sb.append("                        if (pokes != 0) {\n");
        sb.append("                            res_lvl = gs_lvl;\n");
        sb.append("                            res_type = gs_type;\n");
        sb.append("                            early_ret = 1;\n");
        sb.append("                            break;\n");
        sb.append("                        }\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("                if (s_lvl < lowest_surf) lowest_surf = s_lvl;\n");
        sb.append("            }\n");
        sb.append("            if (early_ret == 0) {\n");
        sb.append("                float part_f, full_f;\n");
        sb.append("                if (aq_eval_exclusion(loc_x, loc_y, loc_z, gl_lut_spill) > 0.0f) {\n");
        sb.append("                    part_f = -1.0f;\n");
        sb.append("                    full_f = -1.0f;\n");
        sb.append("                } else {\n");
        sb.append("                    const int dist_below = (lowest_surf + 8) - loc_y;\n");
        sb.append("                    const float factor = (center_under_global != 0) ? aq_clamped_map((float)dist_below, 0.0f, 64.0f, 1.0f, 0.0f) : 0.0f;\n");
        sb.append("                    const float fnv = clamp(aq_eval_floodedness(loc_x, loc_y, loc_z, gl_lut_spill), -1.0f, 1.0f);\n");
        sb.append("                    const float full_th = aq_map(factor, 1.0f, 0.0f, -0.3f, 0.8f);\n");
        sb.append("                    const float part_th = aq_map(factor, 1.0f, 0.0f, -0.8f, 0.4f);\n");
        sb.append("                    part_f = fnv - part_th;\n");
        sb.append("                    full_f = fnv - full_th;\n");
        sb.append("                }\n");
        sb.append("                int f_lvl;\n");
        sb.append("                if (full_f > 0.0f) {\n");
        sb.append("                    f_lvl = g_lvl;\n");
        sb.append("                } else if (part_f > 0.0f) {\n");
        sb.append("                    const int fcx = loc_x >> 4;\n");
        sb.append("                    const int fcy = aq_floor_div(loc_y, 40);\n");
        sb.append("                    const int fcz = loc_z >> 4;\n");
        sb.append("                    const int mid_y = fcy * 40 + 20;\n");
        sb.append("                    const float sp = aq_eval_spread(fcx, fcy, fcz, gl_lut_spill) * 10.0f;\n");
        sb.append("                    const int sp_q = (int)floor(sp / 3.0f) * 3;\n");
        sb.append("                    f_lvl = min(lowest_surf, mid_y + sp_q);\n");
        sb.append("                } else {\n");
        sb.append("                    f_lvl = AQ_WAY_BELOW_MIN_Y;\n");
        sb.append("                }\n");
        sb.append("                uchar f_type = g_type;\n");
        sb.append("                if (f_lvl <= -10 && f_lvl != AQ_WAY_BELOW_MIN_Y && g_type != 3) {\n");
        sb.append("                    const int lcx = loc_x >> 6;\n");
        sb.append("                    const int lcy = aq_floor_div(loc_y, 40);\n");
        sb.append("                    const int lcz = loc_z >> 6;\n");
        sb.append("                    if (fabs(aq_eval_lava(lcx, lcy, lcz, gl_lut_spill)) > 0.3f) {\n");
        sb.append("                        f_type = (uchar)3;\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("                res_lvl = f_lvl;\n");
        sb.append("                res_type = f_type;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        l_aq_lvl[cidx] = res_lvl;\n");
        sb.append("        l_aq_type[cidx] = res_type;\n");
        sb.append("        const uint p_x = (uint)(rel_x & 0xFF);\n");
        sb.append("        const uint p_z = (uint)(rel_z & 0xFF);\n");
        sb.append("        const uint p_y = (uint)(loc_y + 2048) & 0xFFFu;\n");
        sb.append("        const uint p_t = (uint)res_type & 0xFu;\n");
        sb.append("        chunk_cache[4 + (cidx << 1)] = (int)(p_x | (p_z << 8) | (p_y << 16) | (p_t << 28));\n");
        sb.append("        chunk_cache[4 + (cidx << 1) + 1] = res_lvl;\n");
        sb.append("    }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n\n");

        sb.append("    const int bx = orig.x + lx;\n");
        sb.append("    const int bz = orig.z + lz;\n");
        sb.append("    const int xa = (bx - 5) >> 4;\n");
        sb.append("    const int za = (bz - 5) >> 4;\n");
        sb.append("    const int ev_base = batch_idx * AQ_MAX_EVENTS;\n");
        sb.append("    for (int rel_y = total_height - 1; rel_y >= 0; rel_y--) {\n");
        sb.append("        const int v_off = (chunk_base_sec + (rel_y >> 4)) * 4096 + (((rel_y & 15) << 8) | col_idx);\n");
        sb.append("        const uchar raw = out_voxels[v_off];\n");
        sb.append("        if (raw < 128) continue;\n");
        sb.append("        const int by = min_block_y + rel_y;\n");
        sb.append("        if (by > skip_above_y) {\n");
        sb.append("            out_voxels[v_off] = (by < sea_level) ? (uchar)2 : (uchar)0;\n");
        sb.append("            continue;\n");
        sb.append("        }\n");
        sb.append("        const int qd = (int)raw - 128;\n");
        sb.append("        const float density = (qd < 64) ? -((float)qd * 0.015625f) : -(1.0f + (float)(qd - 64) * 0.125f);\n");
        sb.append("        const int ya = aq_floor_div(by + 1, 12);\n");
        sb.append("        int d1 = 2147483647, d2 = 2147483647, d3 = 2147483647, d4 = 2147483647;\n");
        sb.append("        int i1 = 0, i2 = 0, i3 = 0, i4 = 0;\n");
        sb.append("        for (int x1 = 0; x1 <= 1; x1++) {\n");
        sb.append("            const int cx = (xa + x1) - min_gx;\n");
        sb.append("            for (int y1 = -1; y1 <= 1; y1++) {\n");
        sb.append("                const int cy = (ya + y1) - min_gy;\n");
        sb.append("                for (int z1 = 0; z1 <= 1; z1++) {\n");
        sb.append("                    const int cz = (za + z1) - min_gz;\n");
        sb.append("                    const int ci = (cy * gs_z + cz) * gs_x + cx;\n");
        sb.append("                    const int dx = (int)l_aq_x[ci] - lx;\n");
        sb.append("                    const int dy = (int)l_aq_y[ci] - by;\n");
        sb.append("                    const int dz = (int)l_aq_z[ci] - lz;\n");
        sb.append("                    const int dist = dx * dx + dy * dy + dz * dz;\n");
        sb.append("                    if (d1 >= dist) {\n");
        sb.append("                        i4 = i3; i3 = i2; i2 = i1; i1 = ci;\n");
        sb.append("                        d4 = d3; d3 = d2; d2 = d1; d1 = dist;\n");
        sb.append("                    } else if (d2 >= dist) {\n");
        sb.append("                        i4 = i3; i3 = i2; i2 = ci;\n");
        sb.append("                        d4 = d3; d3 = d2; d2 = dist;\n");
        sb.append("                    } else if (d3 >= dist) {\n");
        sb.append("                        i4 = i3; i3 = ci;\n");
        sb.append("                        d4 = d3; d3 = dist;\n");
        sb.append("                    } else if (d4 >= dist) {\n");
        sb.append("                        i4 = ci; d4 = dist;\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        const int lvl1 = l_aq_lvl[i1];\n");
        sb.append("        const uchar typ1 = l_aq_type[i1];\n");
        sb.append("        const float sim12 = 1.0f - (float)(d2 - d1) * 0.04f;\n");
        sb.append("        const uchar f_state = aq_fluid_at(lvl1, typ1, by);\n");
        sb.append("        uchar out_code = f_state;\n");
        sb.append("        int sched_fluid = 0;\n");
        sb.append("        if (sim12 <= 0.0f) {\n");
        sb.append("            if (sim12 >= AQ_FLOWING_SIM) {\n");
        sb.append("                sched_fluid = (lvl1 != l_aq_lvl[i2] || typ1 != l_aq_type[i2]) ? 1 : 0;\n");
        sb.append("            }\n");
        sb.append("        } else {\n");
        sb.append("            int gf_bl; uchar gf_bt;\n");
        sb.append("            aq_global_fluid(by - 1, sea_level, deep_fluid_y, &gf_bl, &gf_bt);\n");
        sb.append("            if (f_state == 2 && aq_fluid_at(gf_bl, gf_bt, by - 1) == 3) {\n");
        sb.append("                sched_fluid = 1;\n");
        sb.append("            } else {\n");
        sb.append("                int has_bar = 0;\n");
        sb.append("                float bar_cache = 0.0f;\n");
        sb.append("                const int lvl2 = l_aq_lvl[i2]; const uchar typ2 = l_aq_type[i2];\n");
        sb.append("                const float b12 = sim12 * aq_calc_pressure(bx, by, bz, lvl1, typ1, lvl2, typ2, &has_bar, &bar_cache, gl_lut_spill);\n");
        sb.append("                if (density + b12 > 0.0f) {\n");
        sb.append("                    out_code = (uchar)1;\n");
        sb.append("                } else {\n");
        sb.append("                    const int lvl3 = l_aq_lvl[i3]; const uchar typ3 = l_aq_type[i3];\n");
        sb.append("                    const float sim13 = 1.0f - (float)(d3 - d1) * 0.04f;\n");
        sb.append("                    int blocked = 0;\n");
        sb.append("                    if (sim13 > 0.0f) {\n");
        sb.append("                        const float b13 = sim12 * sim13 * aq_calc_pressure(bx, by, bz, lvl1, typ1, lvl3, typ3, &has_bar, &bar_cache, gl_lut_spill);\n");
        sb.append("                        if (density + b13 > 0.0f) blocked = 1;\n");
        sb.append("                    }\n");
        sb.append("                    const float sim23 = 1.0f - (float)(d3 - d2) * 0.04f;\n");
        sb.append("                    if (blocked == 0 && sim23 > 0.0f) {\n");
        sb.append("                        const float b23 = sim12 * sim23 * aq_calc_pressure(bx, by, bz, lvl2, typ2, lvl3, typ3, &has_bar, &bar_cache, gl_lut_spill);\n");
        sb.append("                        if (density + b23 > 0.0f) blocked = 1;\n");
        sb.append("                    }\n");
        sb.append("                    if (blocked != 0) {\n");
        sb.append("                        out_code = (uchar)1;\n");
        sb.append("                    } else {\n");
        sb.append("                        const int mf12 = (lvl1 != lvl2 || typ1 != typ2) ? 1 : 0;\n");
        sb.append("                        const int mf23 = (sim23 >= AQ_FLOWING_SIM && (lvl2 != lvl3 || typ2 != typ3)) ? 1 : 0;\n");
        sb.append("                        const int mf13 = (sim13 >= AQ_FLOWING_SIM && (lvl1 != lvl3 || typ1 != typ3)) ? 1 : 0;\n");
        sb.append("                        if (mf12 != 0 || mf23 != 0 || mf13 != 0) {\n");
        sb.append("                            sched_fluid = 1;\n");
        sb.append("                        } else {\n");
        sb.append("                            const float sim14 = 1.0f - (float)(d4 - d1) * 0.04f;\n");
        sb.append("                            if (sim13 >= AQ_FLOWING_SIM && sim14 >= AQ_FLOWING_SIM && (lvl1 != l_aq_lvl[i4] || typ1 != l_aq_type[i4])) {\n");
        sb.append("                                sched_fluid = 1;\n");
        sb.append("                            }\n");
        sb.append("                        }\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        out_voxels[v_off] = out_code;\n");
        sb.append("        if (sched_fluid != 0 && (out_code == 2 || out_code == 3)) {\n");
        sb.append("            const int ei = atomic_inc(&out_carver_counts[batch_idx]);\n");
        sb.append("            if (ei < AQ_MAX_EVENTS) {\n");
        sb.append("                out_carver_events[ev_base + ei] = (uint)(lx | (lz << 4) | (rel_y << 8));\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n\n");
    }

    private static void emitCarverAquiferKernel(
        final StringBuilder sb,
        final long uncarvableMask,
        final long tickingFluidMask,
        final long surfOpaqueMask,
        final long oceanOpaqueMask,
        final int codeGrassBlock,
        final int codeMycelium,
        final int codeDirt
    ) {
        sb.append("__kernel void ").append(KERNEL_NAME).append("(\n");
        sb.append("    __global uchar* restrict out_voxels,\n");
        sb.append("    __global ushort* restrict out_meta,\n");
        sb.append("    __global uchar* restrict out_chunk_flags,\n");
        sb.append("    __global const int4* restrict batch_origins,\n");
        sb.append("    __global const int* restrict carver_meta,\n");
        sb.append("    __global const uint* restrict carver_masks,\n");
        sb.append("    __global uint* restrict out_carver_events,\n");
        sb.append("    __global int* restrict out_carver_counts,\n");
        sb.append("    const int batch_count,\n");
        sb.append("    const int section_count,\n");
        sb.append("    const int sea_level,\n");
        sb.append("    const int deep_fluid_y,\n");
        sb.append("    const int min_block_y,\n");
        sb.append("    const int words_per_col,\n");
        sb.append("    const int do_pack,\n");
        sb.append("    __global const int* restrict gl_lut_spill,\n");
        sb.append("    __global ushort* restrict out_heightmaps,\n");
        sb.append("    __global const int* restrict in_aq_cache,\n");
        sb.append("    const int use_aq_cache)\n");
        sb.append("{\n");
        sb.append("    const int batch_idx = (int)get_group_id(0);\n");
        sb.append("    const int tid = (int)get_local_id(0);\n");
        sb.append("    if (batch_idx >= batch_count) return;\n");
        sb.append("    const int lx = tid & 15;\n");
        sb.append("    const int lz = tid >> 4;\n");
        sb.append("    const int col_idx = tid;\n");
        sb.append("    const int chunk_base_sec = batch_idx * section_count;\n");
        sb.append("    const int mask_slot = carver_meta[batch_idx];\n\n");

        sb.append("    __local short l_surf[513];\n");
        sb.append("    __local int l_max_surf[256];\n");
        sb.append("    __local uchar l_yband_used[48];\n");
        sb.append("    __local short l_aq_x[384];\n");
        sb.append("    __local short l_aq_y[384];\n");
        sb.append("    __local short l_aq_z[384];\n");
        sb.append("    __local int l_aq_lvl[384];\n");
        sb.append("    __local uchar l_aq_type[384];\n\n");

        sb.append("    if (tid < 48) {\n");
        sb.append("        l_yband_used[tid] = (uchar)0;\n");
        sb.append("    }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n\n");

        sb.append("    if (mask_slot >= 0) {\n");
        sb.append("        const int4 orig = batch_origins[batch_idx];\n");
        sb.append("        const int total_height = section_count * 16;\n");
        sb.append("        const int max_block_y = min_block_y + total_height - 1;\n");
        sb.append("        const int min_gx = (orig.x - 5) >> 4;\n");
        sb.append("        const int max_gx = ((orig.x + 10) >> 4) + 1;\n");
        sb.append("        const int gs_x = max_gx - min_gx + 1;\n");
        sb.append("        const int min_gz = (orig.z - 5) >> 4;\n");
        sb.append("        const int max_gz = ((orig.z + 10) >> 4) + 1;\n");
        sb.append("        const int gs_z = max_gz - min_gz + 1;\n");
        sb.append("        const int min_gy = aq_floor_div(min_block_y + 1, 12) - 1;\n");
        sb.append("        const int max_gy = aq_floor_div(max_block_y + 1, 12) + 1;\n");
        sb.append("        const int gs_y = max_gy - min_gy + 1;\n");
        sb.append("        const int total_cells = gs_x * gs_y * gs_z;\n");
        sb.append("        if (total_cells > 384) {\n");
        sb.append("            if (tid == 0) out_carver_counts[batch_idx] = AQ_MAX_EVENTS + 1;\n");
        sb.append("        } else {\n");
        sb.append("        const int surf_min_qx = (min_gx << 2) - 12;\n");
        sb.append("        const int surf_min_qz = (min_gz << 2) - 4;\n\n");
        sb.append("        int skip_above_y = 0;\n");
        sb.append("        __global const int* chunk_cache = in_aq_cache + batch_idx * AQ_CACHE_STRIDE;\n");
        sb.append("        const int cache_valid = (use_aq_cache != 0 && chunk_cache[1] == total_cells) ? 1 : 0;\n");
        sb.append("        if (cache_valid != 0) {\n");
        sb.append("            skip_above_y = chunk_cache[0];\n");
        sb.append("            for (int cidx = tid; cidx < total_cells; cidx += 256) {\n");
        sb.append("                const uint w0 = (uint)chunk_cache[4 + (cidx << 1)];\n");
        sb.append("                l_aq_x[cidx] = (short)((char)(w0 & 0xFFu));\n");
        sb.append("                l_aq_z[cidx] = (short)((char)((w0 >> 8) & 0xFFu));\n");
        sb.append("                l_aq_y[cidx] = (short)(((int)((w0 >> 16) & 0xFFFu)) - 2048);\n");
        sb.append("                l_aq_type[cidx] = (uchar)((w0 >> 28) & 0xFu);\n");
        sb.append("                l_aq_lvl[cidx] = chunk_cache[4 + (cidx << 1) + 1];\n");
        sb.append("            }\n");
        sb.append("        } else {\n");

        // Active Y-band culling: mark only aquifer Y-bands touched by non-zero carving mask words
        sb.append("        __global const uint* col_mask_pre = carver_masks + ((mask_slot * 256 + col_idx) * words_per_col);\n");
        sb.append("        for (int wi = 0; wi < words_per_col; wi++) {\n");
        sb.append("            const uint mw = col_mask_pre[wi];\n");
        sb.append("            if (mw != 0u) {\n");
        sb.append("                const int y_lo = min_block_y + (wi << 5);\n");
        sb.append("                const int y_hi = min(y_lo + 31, max_block_y);\n");
        sb.append("                const int cy_lo = max(0, (aq_floor_div(y_lo + 1, 12) - 1) - min_gy);\n");
        sb.append("                const int cy_hi = min(gs_y - 1, (aq_floor_div(y_hi + 1, 12) + 1) - min_gy);\n");
        sb.append("                for (int cy_u = cy_lo; cy_u <= cy_hi; cy_u++) {\n");
        sb.append("                    l_yband_used[cy_u] = (uchar)1;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        }\n\n");

        // Workgroup-parallel 27x19=513 2D surfaceLevel evaluation (once per chunk across 256 threads)
        sb.append("        for (int idx = tid; idx < 513; idx += 256) {\n");
        sb.append("            const int sx = idx % 27;\n");
        sb.append("            const int sz = idx / 27;\n");
        sb.append("            const int qx = surf_min_qx + sx;\n");
        sb.append("            const int qz = surf_min_qz + sz;\n");
        sb.append("            l_surf[idx] = (short)aq_eval_surface_level(qx << 2, qz << 2, gl_lut_spill);\n");
        sb.append("        }\n");
        sb.append("        barrier(CLK_LOCAL_MEM_FENCE);\n\n");

        // Reduce maxSurfaceLevel over inner 11x11 (sx in 12..22, sz in 4..14)
        sb.append("        int my_max = -2147483647;\n");
        sb.append("        if (tid < 121) {\n");
        sb.append("            const int ix = 12 + (tid % 11);\n");
        sb.append("            const int iz = 4 + (tid / 11);\n");
        sb.append("            my_max = (int)l_surf[iz * 27 + ix];\n");
        sb.append("        }\n");
        sb.append("        l_max_surf[tid] = my_max;\n");
        sb.append("        barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("        for (int stride = 128; stride > 0; stride >>= 1) {\n");
        sb.append("            if (tid < stride) {\n");
        sb.append("                l_max_surf[tid] = max(l_max_surf[tid], l_max_surf[tid + stride]);\n");
        sb.append("            }\n");
        sb.append("            barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("        }\n");
        sb.append("        const int max_adj_surf = l_max_surf[0] + 8;\n");
        sb.append("        const int skip_gy = aq_floor_div(max_adj_surf + 12, 12) + 1;\n");
        sb.append("        skip_above_y = skip_gy * 12 + 10;\n\n");

        // Workgroup-parallel aquifer cell positions & fluid statuses (once per chunk across 256 threads)
        sb.append("        for (int cidx = tid; cidx < total_cells; cidx += 256) {\n");
        sb.append("            const int cx = cidx % gs_x;\n");
        sb.append("            const int cz = (cidx / gs_x) % gs_z;\n");
        sb.append("            const int cy = cidx / (gs_x * gs_z);\n");
        sb.append("            const int gx = min_gx + cx;\n");
        sb.append("            const int gy = min_gy + cy;\n");
        sb.append("            const int gz = min_gz + cz;\n");
        sb.append("            ulong s0 = (ulong)(aq_mth_get_seed(gx, gy, gz) ^ (long)AQ_SEED_LO);\n");
        sb.append("            ulong s1 = AQ_SEED_HI;\n");
        sb.append("            const int loc_x = (gx << 4) + aq_xoro_next_int(&s0, &s1, 10u);\n");
        sb.append("            const int loc_y = gy * 12 + aq_xoro_next_int(&s0, &s1, 9u);\n");
        sb.append("            const int loc_z = (gz << 4) + aq_xoro_next_int(&s0, &s1, 10u);\n");
        sb.append("            l_aq_x[cidx] = (short)(loc_x - orig.x);\n");
        sb.append("            l_aq_y[cidx] = (short)loc_y;\n");
        sb.append("            l_aq_z[cidx] = (short)(loc_z - orig.z);\n");
        sb.append("            int g_lvl; uchar g_type;\n");
        sb.append("            aq_global_fluid(loc_y, sea_level, deep_fluid_y, &g_lvl, &g_type);\n");
        sb.append("            if (l_yband_used[cy] == 0 || loc_y > skip_above_y + 24) {\n");
        sb.append("                l_aq_lvl[cidx] = g_lvl;\n");
        sb.append("                l_aq_type[cidx] = g_type;\n");
        sb.append("                continue;\n");
        sb.append("            }\n");
        sb.append("            int lowest_surf = 2147483647;\n");
        sb.append("            const int top_cell = loc_y + 12;\n");
        sb.append("            const int bot_cell = loc_y - 12;\n");
        sb.append("            int center_under_global = 0;\n");
        sb.append("            int early_ret = 0;\n");
        sb.append("            int res_lvl = g_lvl;\n");
        sb.append("            uchar res_type = g_type;\n");
        sb.append("            for (int oi = 0; oi < 13; oi++) {\n");
        sb.append("                const int samp_x = loc_x + (AQ_SURF_OFF_X[oi] << 4);\n");
        sb.append("                const int samp_z = loc_z + (AQ_SURF_OFF_Z[oi] << 4);\n");
        sb.append("                const int sqx = (samp_x >> 2) - surf_min_qx;\n");
        sb.append("                const int sqz = (samp_z >> 2) - surf_min_qz;\n");
        sb.append("                const int s_lvl = (int)l_surf[sqz * 27 + sqx];\n");
        sb.append("                const int adj_s = s_lvl + 8;\n");
        sb.append("                if (oi == 0 && bot_cell > adj_s) {\n");
        sb.append("                    early_ret = 1;\n");
        sb.append("                    break;\n");
        sb.append("                }\n");
        sb.append("                const int pokes = (top_cell > adj_s) ? 1 : 0;\n");
        sb.append("                if (pokes != 0 || oi == 0) {\n");
        sb.append("                    int gs_lvl; uchar gs_type;\n");
        sb.append("                    aq_global_fluid(adj_s, sea_level, deep_fluid_y, &gs_lvl, &gs_type);\n");
        sb.append("                    if (aq_fluid_at(gs_lvl, gs_type, adj_s) != 0) {\n");
        sb.append("                        if (oi == 0) center_under_global = 1;\n");
        sb.append("                        if (pokes != 0) {\n");
        sb.append("                            res_lvl = gs_lvl;\n");
        sb.append("                            res_type = gs_type;\n");
        sb.append("                            early_ret = 1;\n");
        sb.append("                            break;\n");
        sb.append("                        }\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("                if (s_lvl < lowest_surf) lowest_surf = s_lvl;\n");
        sb.append("            }\n");
        sb.append("            if (early_ret == 0) {\n");
        sb.append("                float part_f, full_f;\n");
        sb.append("                if (aq_eval_exclusion(loc_x, loc_y, loc_z, gl_lut_spill) > 0.0f) {\n");
        sb.append("                    part_f = -1.0f;\n");
        sb.append("                    full_f = -1.0f;\n");
        sb.append("                } else {\n");
        sb.append("                    const int dist_below = (lowest_surf + 8) - loc_y;\n");
        sb.append("                    const float factor = (center_under_global != 0) ? aq_clamped_map((float)dist_below, 0.0f, 64.0f, 1.0f, 0.0f) : 0.0f;\n");
        sb.append("                    const float fnv = clamp(aq_eval_floodedness(loc_x, loc_y, loc_z, gl_lut_spill), -1.0f, 1.0f);\n");
        sb.append("                    const float full_th = aq_map(factor, 1.0f, 0.0f, -0.3f, 0.8f);\n");
        sb.append("                    const float part_th = aq_map(factor, 1.0f, 0.0f, -0.8f, 0.4f);\n");
        sb.append("                    part_f = fnv - part_th;\n");
        sb.append("                    full_f = fnv - full_th;\n");
        sb.append("                }\n");
        sb.append("                int f_lvl;\n");
        sb.append("                if (full_f > 0.0f) {\n");
        sb.append("                    f_lvl = g_lvl;\n");
        sb.append("                } else if (part_f > 0.0f) {\n");
        sb.append("                    const int fcx = loc_x >> 4;\n");
        sb.append("                    const int fcy = aq_floor_div(loc_y, 40);\n");
        sb.append("                    const int fcz = loc_z >> 4;\n");
        sb.append("                    const int mid_y = fcy * 40 + 20;\n");
        sb.append("                    const float sp = aq_eval_spread(fcx, fcy, fcz, gl_lut_spill) * 10.0f;\n");
        sb.append("                    const int sp_q = (int)floor(sp / 3.0f) * 3;\n");
        sb.append("                    f_lvl = min(lowest_surf, mid_y + sp_q);\n");
        sb.append("                } else {\n");
        sb.append("                    f_lvl = AQ_WAY_BELOW_MIN_Y;\n");
        sb.append("                }\n");
        sb.append("                uchar f_type = g_type;\n");
        sb.append("                if (f_lvl <= -10 && f_lvl != AQ_WAY_BELOW_MIN_Y && g_type != 3) {\n");
        sb.append("                    const int lcx = loc_x >> 6;\n");
        sb.append("                    const int lcy = aq_floor_div(loc_y, 40);\n");
        sb.append("                    const int lcz = loc_z >> 6;\n");
        sb.append("                    if (fabs(aq_eval_lava(lcx, lcy, lcz, gl_lut_spill)) > 0.3f) {\n");
        sb.append("                        f_type = (uchar)3;\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("                res_lvl = f_lvl;\n");
        sb.append("                res_type = f_type;\n");
        sb.append("            }\n");
        sb.append("            l_aq_lvl[cidx] = res_lvl;\n");
        sb.append("            l_aq_type[cidx] = res_type;\n");
        sb.append("        }\n");
        sb.append("        }\n");
        sb.append("        barrier(CLK_LOCAL_MEM_FENCE);\n\n");

        // Per-column carving using precomputed __local aquifer cells
        sb.append("        const int bx = orig.x + lx;\n");
        sb.append("        const int bz = orig.z + lz;\n");
        sb.append("        __global const uint* col_mask = carver_masks + ((mask_slot * 256 + col_idx) * words_per_col);\n");
        sb.append("        const int ev_base = batch_idx * AQ_MAX_EVENTS;\n");
        sb.append("        uchar grass_code = (uchar)0;\n");
        sb.append("        const int xa = (bx - 5) >> 4;\n");
        sb.append("        const int za = (bz - 5) >> 4;\n");
        sb.append("        for (int rel_y = total_height - 1; rel_y >= 0; rel_y--) {\n");
        sb.append("            const uint w = col_mask[rel_y >> 5];\n");
        sb.append("            if (w == 0u) {\n");
        sb.append("                grass_code = (uchar)0;\n");
        sb.append("                rel_y &= ~31;\n");
        sb.append("                continue;\n");
        sb.append("            }\n");
        sb.append("            if (((w >> (rel_y & 31)) & 1u) == 0u) {\n");
        sb.append("                grass_code = (uchar)0;\n");
        sb.append("                continue;\n");
        sb.append("            }\n");
        sb.append("            const int by = min_block_y + rel_y;\n");
        sb.append("            const int s_idx = rel_y >> 4;\n");
        sb.append("            const int v_off = (chunk_base_sec + s_idx) * 4096 + (((rel_y & 15) << 8) | (lz << 4) | lx);\n");
        sb.append("            const uchar old_code = out_voxels[v_off];\n");
        sb.append("            if (old_code == 0 && by >= sea_level) continue;\n");
        sb.append("            if ((").append(toUnsignedHex(uncarvableMask)).append(" & (1UL << (uint)old_code)) != 0UL) continue;\n");
        if (codeGrassBlock >= 0 || codeMycelium >= 0) {
            sb.append("            if (");
            if (codeGrassBlock >= 0 && codeMycelium >= 0) {
                sb.append("old_code == ").append(codeGrassBlock).append(" || old_code == ").append(codeMycelium);
            } else if (codeGrassBlock >= 0) {
                sb.append("old_code == ").append(codeGrassBlock);
            } else {
                sb.append("old_code == ").append(codeMycelium);
            }
            sb.append(") grass_code = old_code;\n");
        }
        sb.append("            int carved_code = -1;\n");
        sb.append("            int sched_fluid = 0;\n");
        sb.append("            int gf_lvl; uchar gf_type;\n");
        sb.append("            aq_global_fluid(by, sea_level, deep_fluid_y, &gf_lvl, &gf_type);\n");
        sb.append("            if (by > skip_above_y) {\n");
        sb.append("                carved_code = (int)aq_fluid_at(gf_lvl, gf_type, by);\n");
        sb.append("            } else if (aq_fluid_at(gf_lvl, gf_type, by) == 3) {\n");
        sb.append("                carved_code = 3;\n");
        sb.append("            } else {\n");
        sb.append("                const int ya = aq_floor_div(by + 1, 12);\n");
        sb.append("                int d1 = 2147483647, d2 = 2147483647, d3 = 2147483647, d4 = 2147483647;\n");
        sb.append("                int i1 = 0, i2 = 0, i3 = 0, i4 = 0;\n");
        sb.append("                for (int x1 = 0; x1 <= 1; x1++) {\n");
        sb.append("                    const int cx = (xa + x1) - min_gx;\n");
        sb.append("                    for (int y1 = -1; y1 <= 1; y1++) {\n");
        sb.append("                        const int cy = (ya + y1) - min_gy;\n");
        sb.append("                        for (int z1 = 0; z1 <= 1; z1++) {\n");
        sb.append("                            const int cz = (za + z1) - min_gz;\n");
        sb.append("                            const int ci = (cy * gs_z + cz) * gs_x + cx;\n");
        sb.append("                            const int dx = (int)l_aq_x[ci] - lx;\n");
        sb.append("                            const int dy = (int)l_aq_y[ci] - by;\n");
        sb.append("                            const int dz = (int)l_aq_z[ci] - lz;\n");
        sb.append("                            const int dist = dx * dx + dy * dy + dz * dz;\n");
        sb.append("                            if (d1 >= dist) {\n");
        sb.append("                                i4 = i3; i3 = i2; i2 = i1; i1 = ci;\n");
        sb.append("                                d4 = d3; d3 = d2; d2 = d1; d1 = dist;\n");
        sb.append("                            } else if (d2 >= dist) {\n");
        sb.append("                                i4 = i3; i3 = i2; i2 = ci;\n");
        sb.append("                                d4 = d3; d3 = d2; d2 = dist;\n");
        sb.append("                            } else if (d3 >= dist) {\n");
        sb.append("                                i4 = i3; i3 = ci;\n");
        sb.append("                                d4 = d3; d3 = dist;\n");
        sb.append("                            } else if (d4 >= dist) {\n");
        sb.append("                                i4 = ci; d4 = dist;\n");
        sb.append("                            }\n");
        sb.append("                        }\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("                const int lvl1 = l_aq_lvl[i1];\n");
        sb.append("                const uchar typ1 = l_aq_type[i1];\n");
        sb.append("                const float sim12 = 1.0f - (float)(d2 - d1) * 0.04f;\n");
        sb.append("                const uchar f_state = aq_fluid_at(lvl1, typ1, by);\n");
        sb.append("                if (sim12 <= 0.0f) {\n");
        sb.append("                    if (sim12 >= AQ_FLOWING_SIM) {\n");
        sb.append("                        sched_fluid = (lvl1 != l_aq_lvl[i2] || typ1 != l_aq_type[i2]) ? 1 : 0;\n");
        sb.append("                    }\n");
        sb.append("                    carved_code = (int)f_state;\n");
        sb.append("                } else {\n");
        sb.append("                    int gf_bl; uchar gf_bt;\n");
        sb.append("                    aq_global_fluid(by - 1, sea_level, deep_fluid_y, &gf_bl, &gf_bt);\n");
        sb.append("                    if (f_state == 2 && aq_fluid_at(gf_bl, gf_bt, by - 1) == 3) {\n");
        sb.append("                        sched_fluid = 1;\n");
        sb.append("                        carved_code = (int)f_state;\n");
        sb.append("                    } else {\n");
        sb.append("                        int has_bar = 0;\n");
        sb.append("                        float bar_cache = 0.0f;\n");
        sb.append("                        const int lvl2 = l_aq_lvl[i2]; const uchar typ2 = l_aq_type[i2];\n");
        sb.append("                        const float b12 = sim12 * aq_calc_pressure(bx, by, bz, lvl1, typ1, lvl2, typ2, &has_bar, &bar_cache, gl_lut_spill);\n");
        sb.append("                        if (b12 <= 0.0f) {\n");
        sb.append("                            const int lvl3 = l_aq_lvl[i3]; const uchar typ3 = l_aq_type[i3];\n");
        sb.append("                            const float sim13 = 1.0f - (float)(d3 - d1) * 0.04f;\n");
        sb.append("                            int blocked = 0;\n");
        sb.append("                            if (sim13 > 0.0f) {\n");
        sb.append("                                const float b13 = sim12 * sim13 * aq_calc_pressure(bx, by, bz, lvl1, typ1, lvl3, typ3, &has_bar, &bar_cache, gl_lut_spill);\n");
        sb.append("                                if (b13 > 0.0f) blocked = 1;\n");
        sb.append("                            }\n");
        sb.append("                            const float sim23 = 1.0f - (float)(d3 - d2) * 0.04f;\n");
        sb.append("                            if (blocked == 0 && sim23 > 0.0f) {\n");
        sb.append("                                const float b23 = sim12 * sim23 * aq_calc_pressure(bx, by, bz, lvl2, typ2, lvl3, typ3, &has_bar, &bar_cache, gl_lut_spill);\n");
        sb.append("                                if (b23 > 0.0f) blocked = 1;\n");
        sb.append("                            }\n");
        sb.append("                            if (blocked == 0) {\n");
        sb.append("                                const int mf12 = (lvl1 != lvl2 || typ1 != typ2) ? 1 : 0;\n");
        sb.append("                                const int mf23 = (sim23 >= AQ_FLOWING_SIM && (lvl2 != lvl3 || typ2 != typ3)) ? 1 : 0;\n");
        sb.append("                                const int mf13 = (sim13 >= AQ_FLOWING_SIM && (lvl1 != lvl3 || typ1 != typ3)) ? 1 : 0;\n");
        sb.append("                                if (mf12 != 0 || mf23 != 0 || mf13 != 0) {\n");
        sb.append("                                    sched_fluid = 1;\n");
        sb.append("                                } else {\n");
        sb.append("                                    const float sim14 = 1.0f - (float)(d4 - d1) * 0.04f;\n");
        sb.append("                                    if (sim13 >= AQ_FLOWING_SIM && sim14 >= AQ_FLOWING_SIM && (lvl1 != l_aq_lvl[i4] || typ1 != l_aq_type[i4])) {\n");
        sb.append("                                        sched_fluid = 1;\n");
        sb.append("                                    }\n");
        sb.append("                                }\n");
        sb.append("                                carved_code = (int)f_state;\n");
        sb.append("                            }\n");
        sb.append("                        }\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("            if (carved_code >= 0) {\n");
        sb.append("                if ((uchar)carved_code != old_code) {\n");
        sb.append("                    out_voxels[v_off] = (uchar)carved_code;\n");
        sb.append("                }\n");
        sb.append("                const int is_fluid = (carved_code == 2 || carved_code == 3) ? 1 : 0;\n");
        sb.append("                if (sched_fluid != 0 && is_fluid != 0) {\n");
        sb.append("                    const int ei = atomic_inc(&out_carver_counts[batch_idx]);\n");
        sb.append("                    if (ei < AQ_MAX_EVENTS) {\n");
        sb.append("                        out_carver_events[ev_base + ei] = (uint)(lx | (lz << 4) | (rel_y << 8));\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        if (codeDirt >= 0) {
            sb.append("                if (grass_code != 0 && is_fluid == 0 && rel_y > 0) {\n");
            sb.append("                    const int b_rel = rel_y - 1;\n");
            sb.append("                    if (((col_mask[b_rel >> 5] >> (b_rel & 31)) & 1u) == 0u) {\n");
            sb.append("                        const int b_off = (chunk_base_sec + (b_rel >> 4)) * 4096 + (((b_rel & 15) << 8) | (lz << 4) | lx);\n");
            sb.append("                        if (out_voxels[b_off] == ").append(codeDirt).append(") {\n");
            sb.append("                            out_voxels[b_off] = grass_code;\n");
            sb.append("                        }\n");
            sb.append("                    }\n");
            sb.append("                }\n");
        }
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // Fast top-down heightmap scan when Pass 3B is the final voxel-mutating pass (packing runs in Pass 5)
        sb.append("    if (do_pack != 0) {\n");
        sb.append("        int col_hm_surf = 0;\n");
        sb.append("        int col_hm_ocean = 0;\n");
        sb.append("        for (int ry = section_count * 16 - 1; ry >= 0; ry--) {\n");
        sb.append("            const uint c = (uint)out_voxels[(chunk_base_sec + (ry >> 4)) * 4096 + (((ry & 15) << 8) | col_idx)];\n");
        sb.append("            if (c != 0u) {\n");
        sb.append("                const ulong c_bit = (1UL << c);\n");
        sb.append("                if (col_hm_surf == 0 && (").append(toUnsignedHex(surfOpaqueMask)).append(" & c_bit) != 0UL) {\n");
        sb.append("                    col_hm_surf = ry + 1;\n");
        sb.append("                }\n");
        sb.append("                if ((").append(toUnsignedHex(oceanOpaqueMask)).append(" & c_bit) != 0UL) {\n");
        sb.append("                    col_hm_ocean = ry + 1;\n");
        sb.append("                    if (col_hm_surf == 0) col_hm_surf = ry + 1;\n");
        sb.append("                    break;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        out_heightmaps[batch_idx * 512 + col_idx] = (ushort)col_hm_surf;\n");
        sb.append("        out_heightmaps[batch_idx * 512 + 256 + col_idx] = (ushort)col_hm_ocean;\n");
        sb.append("    }\n");
        sb.append("}\n");
    }

    private static @Nullable String buildSingleAuxCallExpr(final String prefixSrc) {
        final String[] types = {
            "static inline long ", "static inline float ", "static inline double ",
            "static long ", "static float ", "static double ",
            "inline long ", "inline float ", "inline double "
        };
        String evalFn = null;
        boolean returnsQ32 = false;
        int bestPos = -1;
        for (final String prefix : types) {
            int pos = 0;
            while ((pos = prefixSrc.indexOf(prefix, pos)) >= 0) {
                final int nameStart = pos + prefix.length();
                final int paren = prefixSrc.indexOf('(', nameStart);
                if (paren > nameStart) {
                    final String candidate = prefixSrc.substring(nameStart, paren).trim();
                    if (candidate.endsWith("_evalDensity") || candidate.equals("evalDensity")) {
                        if (pos > bestPos) {
                            bestPos = pos;
                            evalFn = candidate;
                            returnsQ32 = prefix.contains("long ");
                        }
                    }
                }
                pos = nameStart;
            }
        }
        if (evalFn == null) {
            return null;
        }
        final int sigIdx = prefixSrc.indexOf(evalFn + "(");
        final int sigEnd = sigIdx >= 0 ? prefixSrc.indexOf(')', sigIdx) : -1;
        final boolean acceptsSpill = sigIdx >= 0 && sigEnd > sigIdx
            && prefixSrc.substring(sigIdx, sigEnd).contains("gl_lut_spill");
        final String call = acceptsSpill
            ? evalFn + "(bx, by, bz, gl_lut_spill)"
            : evalFn + "(bx, by, bz)";
        return returnsQ32 ? ("q32_to_f32(" + call + ")") : ("(float)(" + call + ")");
    }

    private static String toUnsignedHex(final long v) {
        return "0x" + Long.toHexString(v) + "UL";
    }

    private static String sha256(final String source, final int[] spilledLuts) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(source.getBytes(StandardCharsets.UTF_8));
            for (final int w : spilledLuts) {
                md.update((byte) (w >>> 24));
                md.update((byte) (w >>> 16));
                md.update((byte) (w >>> 8));
                md.update((byte) w);
            }
            final byte[] digest = md.digest();
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
