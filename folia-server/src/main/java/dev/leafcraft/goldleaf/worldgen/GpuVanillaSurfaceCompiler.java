package dev.leafcraft.goldleaf.worldgen;

import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter;
import io.github.dailystruggle.bytecode.transpile.ConceptOptimizerPass;
import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.FindTopSurfaceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.condition.AbovePreliminarySurfaceCondition;
import net.minecraft.world.level.levelgen.material.condition.BiomeCondition;
import net.minecraft.world.level.levelgen.material.condition.HoleCondition;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.condition.NoiseThresholdCondition;
import net.minecraft.world.level.levelgen.material.condition.NotCondition;
import net.minecraft.world.level.levelgen.material.condition.SteepCondition;
import net.minecraft.world.level.levelgen.material.condition.StoneDepthCondition;
import net.minecraft.world.level.levelgen.material.condition.TemperatureCondition;
import net.minecraft.world.level.levelgen.material.condition.VerticalGradientCondition;
import net.minecraft.world.level.levelgen.material.condition.WaterCondition;
import net.minecraft.world.level.levelgen.material.condition.YCondition;
import net.minecraft.world.level.levelgen.material.rule.BandlandsRule;
import net.minecraft.world.level.levelgen.material.rule.BlockRule;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.Nullable;

/**
 * Compiles Mojang's live {@code finalDensity} + {@link MaterialRule} AST ({@code Overworld},
 * {@code Nether}, {@code End}, and datapack rules) into a single vanilla-exact OpenCL C99 kernel
 * ({@code fill_vanilla_surface_batch_u8}).
 *
 * <p>Uses exact {@code Xoroshiro128++} positional PRNG, exact {@code BiomeManager} 8-corner zoom
 * over a {@code 6×6} halo of chunk quart biomes, exact {@code MaterialSystem#buildSurface} column
 * state tracking, and shared-LUT {@code INT64_Q32_FP32} noise evaluation. Chunks containing
 * {@code ERODED_BADLANDS}, {@code FROZEN_OCEAN}, or {@code DEEP_FROZEN_OCEAN} surface columns set
 * bit 0 in {@code out_chunk_flags} so CPU runs the rare pillar/iceberg extension cleanly.</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017, leafgen-ADR-008
 * @trace S-004
 */
public final class GpuVanillaSurfaceCompiler {

    public static final String TERRAIN_KERNEL_NAME = "fill_terrain_u8";
    public static final String SURFACE_KERNEL_NAME = "apply_vanilla_surface_u8";
    public static final String PACK_KERNEL_NAME = "pack_sections_u8";
    public static final int UNREFERENCED_BIOME_ID = 255;
    private static final int MAX_BLOCK_PALETTE = 255;
    private static final int MAX_BIOME_PALETTE = 254;

    private GpuVanillaSurfaceCompiler() {
    }

    public record CompiledVanillaSurfaceProgram(
        OpenClBackendEmitter.DensityVolumeProgram terrainProgram,
        OpenClBackendEmitter.DensityVolumeProgram surfaceProgram,
        OpenClBackendEmitter.DensityVolumeProgram packProgram,
        BlockState[] blockPalette,
        Map<ResourceKey<Biome>, Integer> biomeKeyToId,
        int specialExtensionBiomeCount,
        java.util.concurrent.ConcurrentHashMap<Holder<Biome>, Byte> holderIdCache
    ) {
        public CompiledVanillaSurfaceProgram(
            final OpenClBackendEmitter.DensityVolumeProgram terrainProgram,
            final OpenClBackendEmitter.DensityVolumeProgram surfaceProgram,
            final OpenClBackendEmitter.DensityVolumeProgram packProgram,
            final BlockState[] blockPalette,
            final Map<ResourceKey<Biome>, Integer> biomeKeyToId,
            final int specialExtensionBiomeCount
        ) {
            this(terrainProgram, surfaceProgram, packProgram, blockPalette, biomeKeyToId, specialExtensionBiomeCount,
                new java.util.concurrent.ConcurrentHashMap<>(128));
        }

        public byte mapBiomeByte(final Holder<Biome> biome) {
            final Byte cached = this.holderIdCache.get(biome);
            if (cached != null) {
                return cached;
            }
            int mapped = UNREFERENCED_BIOME_ID;
            final var keyOpt = biome.unwrapKey();
            if (keyOpt.isPresent()) {
                final Integer id = this.biomeKeyToId.get(keyOpt.get());
                if (id != null) {
                    mapped = id;
                }
            }
            final byte b = (byte) mapped;
            this.holderIdCache.putIfAbsent(biome, b);
            return b;
        }

        public int mapBiome(final Holder<Biome> biome) {
            return mapBiomeByte(biome) & 0xFF;
        }
    }

    private static final class CompilationContext {
        final RandomState randomState;
        final WorldGenerationContext worldGenContext;
        final boolean generateFlatBedrock;
        final MaterialSystem materialSystem;
        final int seaLevel;
        final List<BlockState> blockPalette = new ArrayList<>(32);
        final Map<BlockState, Integer> blockToId = new IdentityHashMap<>();
        final Map<ResourceKey<Biome>, Integer> biomeKeyToId = new LinkedHashMap<>();
        final List<DensityFunction> auxDensityFunctions = new ArrayList<>(16);
        final Map<DensityFunction, Integer> auxDensityDedup = new IdentityHashMap<>();
        final Map<ResourceKey<NormalNoise>, Integer> noise2dToAuxIdx = new LinkedHashMap<>();
        final Map<ResourceKey<NormalNoise>, Integer> noise3dToAuxIdx = new LinkedHashMap<>();
        boolean hasOreVeinRules = false;
        int surfaceNoiseAuxIdx = -1;
        int surfaceSecondaryAuxIdx = -1;
        int clayBandsOffsetAuxIdx = -1;
        int oreVeininessAuxIdx = -1;
        int oreVeinAAuxIdx = -1;
        int oreVeinBAuxIdx = -1;
        int oreGapAuxIdx = -1;
        int badlandsSurfaceAuxIdx = -1;
        int badlandsPillarAuxIdx = -1;
        int badlandsPillarRoofAuxIdx = -1;
        int icebergSurfaceAuxIdx = -1;
        int icebergPillarAuxIdx = -1;
        int icebergPillarRoofAuxIdx = -1;
        int snowBlockId = -1;
        int packedIceId = -1;
        long oreSeedLo = 0L;
        long oreSeedHi = 0L;
        boolean usesClayBands = false;
        boolean usesSurfaceSecondary = false;
        boolean usesPrelimSurface = false;
        boolean unsupported = false;
        String unsupportedReason = "";

        CompilationContext(
            final RandomState randomState,
            final WorldGenerationContext worldGenContext,
            final NoiseGeneratorSettings settings,
            final boolean generateFlatBedrock
        ) {
            this.randomState = randomState;
            this.worldGenContext = worldGenContext;
            this.generateFlatBedrock = generateFlatBedrock;
            this.materialSystem = randomState.surfaceSystem();
            this.seaLevel = settings.seaLevel();
            addBlock(Blocks.AIR.defaultBlockState());       // 0
            addBlock(settings.defaultBlock());              // 1
            addBlock(settings.defaultFluid());              // 2
            addBlock(Blocks.LAVA.defaultBlockState());      // 3
        }

        int addBlock(final BlockState state) {
            final Integer existing = this.blockToId.get(state);
            if (existing != null) {
                return existing;
            }
            if (this.blockPalette.size() >= MAX_BLOCK_PALETTE) {
                this.unsupported = true;
                this.unsupportedReason = "BlockState palette exceeded " + MAX_BLOCK_PALETTE;
                return 0;
            }
            final int id = this.blockPalette.size();
            this.blockPalette.add(state);
            this.blockToId.put(state, id);
            return id;
        }

        int addBiomeKey(final ResourceKey<Biome> key) {
            final Integer existing = this.biomeKeyToId.get(key);
            if (existing != null) {
                return existing;
            }
            if (this.biomeKeyToId.size() >= MAX_BIOME_PALETTE) {
                this.unsupported = true;
                this.unsupportedReason = "Biome palette exceeded " + MAX_BIOME_PALETTE;
                return UNREFERENCED_BIOME_ID;
            }
            final int id = this.biomeKeyToId.size();
            this.biomeKeyToId.put(key, id);
            return id;
        }

        int addAuxDensity(final DensityFunction fn) {
            final Integer existing = this.auxDensityDedup.get(fn);
            if (existing != null) {
                return existing;
            }
            final int idx = this.auxDensityFunctions.size();
            this.auxDensityFunctions.add(fn);
            this.auxDensityDedup.put(fn, idx);
            return idx;
        }

        int getOrAddNoise2d(final ResourceKey<NormalNoise> key) {
            final Integer existing = this.noise2dToAuxIdx.get(key);
            if (existing != null) {
                return existing;
            }
            final Holder<NormalNoise> holder = this.randomState.goldleaf$noises().getOrThrow(key);
            final int idx = addAuxDensity(DensityFunctions.noise(holder, 1.0, 1.0));
            this.noise2dToAuxIdx.put(key, idx);
            return idx;
        }

        int getOrAddNoise3d(final ResourceKey<NormalNoise> key) {
            final Integer existing = this.noise3dToAuxIdx.get(key);
            if (existing != null) {
                return existing;
            }
            final Holder<NormalNoise> holder = this.randomState.goldleaf$noises().getOrThrow(key);
            final int idx = addAuxDensity(DensityFunctions.noise(holder, 1.0, 1.0));
            this.noise3dToAuxIdx.put(key, idx);
            return idx;
        }
    }

    public static @Nullable CompiledVanillaSurfaceProgram compile(
        final NoiseGeneratorSettings settings,
        final RandomState randomState,
        final WorldGenerationContext worldGenContext,
        final OpenClBackendEmitter openClEmitter,
        final ConceptBackendEmitter.GpuExactArithmeticMode arithmeticMode,
        final String moduleBaseName
    ) {
        return compile(settings, randomState, worldGenContext, false, openClEmitter, arithmeticMode, moduleBaseName);
    }

    public static @Nullable CompiledVanillaSurfaceProgram compile(
        final NoiseGeneratorSettings settings,
        final RandomState randomState,
        final WorldGenerationContext worldGenContext,
        final boolean generateFlatBedrock,
        final OpenClBackendEmitter openClEmitter,
        final ConceptBackendEmitter.GpuExactArithmeticMode arithmeticMode,
        final String moduleBaseName
    ) {
        return compile(
            settings,
            randomState,
            worldGenContext,
            generateFlatBedrock,
            false,
            false,
            openClEmitter,
            arithmeticMode,
            moduleBaseName
        );
    }

    public static @Nullable CompiledVanillaSurfaceProgram compile(
        final NoiseGeneratorSettings settings,
        final RandomState randomState,
        final WorldGenerationContext worldGenContext,
        final boolean generateFlatBedrock,
        final boolean macroNoiseOctaves,
        final boolean includeFeatureBlocks,
        final OpenClBackendEmitter openClEmitter,
        final ConceptBackendEmitter.GpuExactArithmeticMode arithmeticMode,
        final String moduleBaseName
    ) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(randomState, "randomState");
        Objects.requireNonNull(worldGenContext, "worldGenContext");
        Objects.requireNonNull(openClEmitter, "openClEmitter");

        final PositionalRandomFactory noiseRandom = randomState.surfaceSystem().goldleaf$noiseRandom();
        if (!(noiseRandom instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory xorNoiseRandom)) {
            org.slf4j.LoggerFactory.getLogger(GpuVanillaSurfaceCompiler.class).info(
                "[GoldLeaf-OpenCL] Vanilla surface compiler skipped '{}': noiseRandom is {}",
                moduleBaseName, noiseRandom.getClass().getSimpleName()
            );
            return null;
        }

        final CompilationContext ctx = new CompilationContext(randomState, worldGenContext, settings, generateFlatBedrock);
        // Reserve special extension biomes first (indices 0, 1, 2)
        final int erodedBadlandsId = ctx.addBiomeKey(Biomes.ERODED_BADLANDS);
        final int frozenOceanId = ctx.addBiomeKey(Biomes.FROZEN_OCEAN);
        final int deepFrozenOceanId = ctx.addBiomeKey(Biomes.DEEP_FROZEN_OCEAN);

        // Mandatory surface noise (for surfaceDepth)
        ctx.surfaceNoiseAuxIdx = ctx.addAuxDensity(
            DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.SURFACE), 1.0, 1.0)
        );

        final MaterialRule rootRule = settings.materialRule().value();
        collectRuleResources(rootRule, ctx);
        if (includeFeatureBlocks && !ctx.unsupported) {
            for (final BlockState fb : GpuFeaturePassCompiler.featureBlocks()) {
                ctx.addBlock(fb);
            }
            for (final ResourceKey<Biome> biomeKey : GpuFeaturePassCompiler.featureBiomes()) {
                ctx.addBiomeKey(biomeKey);
            }
        }
        if (ctx.unsupported) {
            org.slf4j.LoggerFactory.getLogger(GpuVanillaSurfaceCompiler.class).info(
                "[GoldLeaf-OpenCL] Vanilla surface compiler skipped '{}': {}",
                moduleBaseName, ctx.unsupportedReason
            );
            return null;
        }

        if (ctx.hasOreVeinRules) {
            ctx.oreVeininessAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.ORE_VEININESS), 1.5, 1.5)
            );
            ctx.oreVeinAAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.ORE_VEIN_A), 4.0, 4.0)
            );
            ctx.oreVeinBAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.ORE_VEIN_B), 4.0, 4.0)
            );
            ctx.oreGapAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.ORE_GAP), 1.0, 1.0)
            );
        }
        if (ctx.usesSurfaceSecondary) {
            ctx.surfaceSecondaryAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.SURFACE_SECONDARY), 1.0, 1.0)
            );
        }
        if (ctx.usesClayBands) {
            ctx.clayBandsOffsetAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.CLAY_BANDS_OFFSET), 1.0, 1.0)
            );
            ctx.badlandsSurfaceAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.BADLANDS_SURFACE), 1.0, 1.0)
            );
            ctx.badlandsPillarAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.BADLANDS_PILLAR), 0.2, 0.2)
            );
            ctx.badlandsPillarRoofAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.BADLANDS_PILLAR_ROOF), 0.75, 0.75)
            );
            ctx.icebergSurfaceAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.ICEBERG_SURFACE), 1.0, 1.0)
            );
            ctx.icebergPillarAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.ICEBERG_PILLAR), 1.28, 1.28)
            );
            ctx.icebergPillarRoofAuxIdx = ctx.addAuxDensity(
                DensityFunctions.noise(randomState.goldleaf$noises().getOrThrow(Noises.ICEBERG_PILLAR_ROOF), 1.17, 1.17)
            );
            ctx.snowBlockId = ctx.addBlock(Blocks.SNOW_BLOCK.defaultBlockState());
            ctx.packedIceId = ctx.addBlock(Blocks.PACKED_ICE.defaultBlockState());
            for (final BlockState band : ctx.materialSystem.goldleaf$clayBands()) {
                ctx.addBlock(band);
            }
            if (ctx.unsupported) {
                return null;
            }
        }

        // Lower primary finalDensity with coarse 5x49x5 split
        final DensityFunction.CompileContext compileCtx = randomState.paper$compileContext();
        final DensityFunctionIrLowerer.SplitLoweringResult primarySplit = DensityFunctionIrLowerer.lowerSplitForSectionFill(
            settings.noiseRouter().finalDensity(),
            compileCtx,
            moduleBaseName + "_final",
            macroNoiseOctaves
        );
        if (!primarySplit.isSupported() || primarySplit.module() == null || primarySplit.coarseSpec() == null) {
            org.slf4j.LoggerFactory.getLogger(GpuVanillaSurfaceCompiler.class).info(
                "[GoldLeaf-OpenCL] Vanilla surface compiler skipped '{}': primarySplit unsupported ({})",
                moduleBaseName, primarySplit.unsupportedReason()
            );
            return null;
        }

        final ConceptBackendEmitter.EmitOptions emitOptions = ConceptBackendEmitter.EmitOptions.of(
            arithmeticMode,
            ConceptBackendEmitter.BatchLayout.SECTION_PALETTE_FILL_U2
        );
        final BytecodeConceptIr.ConceptModule primaryOptimized = ConceptOptimizerPass.optimize(primarySplit.module(), emitOptions);

        // Lower all auxiliary DensityFunctions
        final List<BytecodeConceptIr.ConceptModule> auxModules = new ArrayList<>(ctx.auxDensityFunctions.size());
        for (int i = 0; i < ctx.auxDensityFunctions.size(); i++) {
            final DensityFunctionIrLowerer.LoweringResult res = DensityFunctionIrLowerer.lower(
                ctx.auxDensityFunctions.get(i),
                compileCtx,
                moduleBaseName + "_aux_" + i,
                true
            );
            if (!res.isSupported() || res.module() == null) {
                org.slf4j.LoggerFactory.getLogger(GpuVanillaSurfaceCompiler.class).info(
                    "[GoldLeaf-OpenCL] Vanilla surface compiler skipped '{}': aux[{}] unsupported ({})",
                    moduleBaseName, i, res.unsupportedReason()
                );
                return null;
            }
            auxModules.add(ConceptOptimizerPass.optimize(res.module(), emitOptions));
        }

        // 1. Emit lean primary terrain program (fill_terrain_u8, batchCount * sectionCount workgroups -> 100% SM occupancy)
        final OpenClBackendEmitter.DensityVolumeProgram primaryFillProg = openClEmitter.emitInterpolatedSectionPaletteFillProgram(
            primaryOptimized,
            emitOptions,
            primarySplit.coarseSpec()
        );
        final String primarySrc = primaryFillProg.sourceCode();
        final int kernelIdx = primarySrc.indexOf("__kernel void fill_section_palette_batch_u2(");
        if (kernelIdx < 0) {
            org.slf4j.LoggerFactory.getLogger(GpuVanillaSurfaceCompiler.class).info(
                "[GoldLeaf-OpenCL] Vanilla surface compiler skipped '{}': primary kernel entry not found",
                moduleBaseName
            );
            return null;
        }
        final String primaryPrefix = primarySrc.substring(0, kernelIdx);
        final OpenClBackendEmitter.CoarseInterpSpec coarseSpec = primarySplit.coarseSpec();
        final String combinerCallExpr = buildCombinerCallExpr(primaryPrefix, coarseSpec);
        if (combinerCallExpr == null) {
            org.slf4j.LoggerFactory.getLogger(GpuVanillaSurfaceCompiler.class).info(
                "[GoldLeaf-OpenCL] Vanilla surface compiler skipped '{}': combiner eval function not found",
                moduleBaseName
            );
            return null;
        }

        final StringBuilder terrainSb = new StringBuilder(primaryPrefix.length() + 4096);
        terrainSb.append(primaryPrefix);
        emitTerrainU8Kernel(terrainSb, primaryPrefix, coarseSpec, combinerCallExpr);
        final String terrainSource = terrainSb.toString();
        final int[] terrainSpill = primaryFillProg.spilledLutWords();
        final OpenClBackendEmitter.DensityVolumeProgram terrainProg = new OpenClBackendEmitter.DensityVolumeProgram(
            TERRAIN_KERNEL_NAME,
            terrainSource,
            sha256(arithmeticMode.name() + "\n" + terrainSource, terrainSpill),
            terrainSpill,
            primaryFillProg.usedConstantBytes(),
            primaryFillProg.spilledLutCount(),
            primaryFillProg.deduplicatedLutCount()
        );

        // 2. Emit lean surface-only program (apply_vanilla_surface_u8, ZERO finalDensity bloat!)
        // Use aux[0] (Noises.SURFACE) as primary module and aux[1..] as auxiliary modules sharing one LUT allocator
        final List<BytecodeConceptIr.ConceptModule> remainingAux = auxModules.size() > 1
            ? auxModules.subList(1, auxModules.size())
            : List.of();
        final OpenClBackendEmitter.MultiModuleSectionProgram surfMulti = openClEmitter.emitSectionFillWithAuxModules(
            auxModules.get(0),
            null,
            remainingAux,
            emitOptions
        );
        final String surfPrimarySrc = surfMulti.primarySourceCode();
        final int surfKernIdx = surfPrimarySrc.indexOf("__kernel void fill_section_palette_batch_u2(");
        if (surfKernIdx < 0) {
            return null;
        }
        final String surfPrimaryPrefix = surfPrimarySrc.substring(0, surfKernIdx);
        final String aux0CallExpr = buildSingleAuxCallExpr(surfPrimaryPrefix);
        if (aux0CallExpr == null) {
            return null;
        }
        final List<String> allAuxCalls = new ArrayList<>(auxModules.size());
        allAuxCalls.add(aux0CallExpr);
        allAuxCalls.addAll(surfMulti.auxEntryCallExprs());

        final String auxHelpersNonInline = surfMulti.auxiliaryHelpersSource().replace("static inline ", "static ");
        final StringBuilder surfSb = new StringBuilder(surfPrimaryPrefix.length() + auxHelpersNonInline.length() + 16384);
        surfSb.append(surfPrimaryPrefix);
        surfSb.append("\n// === Auxiliary MaterialRule Noise Helpers ===\n");
        surfSb.append(auxHelpersNonInline);
        surfSb.append('\n');

        emitXoroshiroAndBiomeHelpers(surfSb, ctx, erodedBadlandsId, frozenOceanId, deepFrozenOceanId);
        emitMaterialRuleEvaluator(surfSb, rootRule, ctx, allAuxCalls);
        emitSurfaceApplyKernel(
            surfSb,
            ctx,
            allAuxCalls,
            xorNoiseRandom.goldleaf$seedLo(),
            xorNoiseRandom.goldleaf$seedHi()
        );

        final String surfSource = surfSb.toString();
        final int[] surfSpill = surfMulti.spilledLutWords();
        final OpenClBackendEmitter.DensityVolumeProgram surfaceProg = new OpenClBackendEmitter.DensityVolumeProgram(
            SURFACE_KERNEL_NAME,
            surfSource,
            sha256(arithmeticMode.name() + "\n" + surfSource, surfSpill),
            surfSpill,
            surfMulti.usedConstantBytes(),
            surfMulti.spilledLutCount(),
            surfMulti.deduplicatedLutCount()
        );
        final OpenClBackendEmitter.DensityVolumeProgram packProg = buildPackSectionsProgram(ctx.blockPalette);
        return new CompiledVanillaSurfaceProgram(
            terrainProg,
            surfaceProg,
            packProg,
            ctx.blockPalette.toArray(new BlockState[0]),
            Map.copyOf(ctx.biomeKeyToId),
            3
        );
    }

    private static void collectRuleResources(final MaterialRule rule, final CompilationContext ctx) {
        if (ctx.unsupported) {
            return;
        }
        if (rule instanceof MaterialRule.HolderHolder hh) {
            collectRuleResources(hh.holder().value(), ctx);
        } else if (rule instanceof SequenceRule seq) {
            for (final MaterialRule child : seq.sequence()) {
                collectRuleResources(child, ctx);
            }
        } else if (rule instanceof ConditionRule cond) {
            collectConditionResources(cond.ifTrue(), ctx);
            collectRuleResources(cond.thenRun(), ctx);
        } else if (rule instanceof BlockRule blk) {
            ctx.addBlock(blk.resultState());
        } else if (rule instanceof BandlandsRule) {
            ctx.usesClayBands = true;
        } else if (rule instanceof OreVeinRule ore) {
            ctx.addBlock(ore.oreBlock());
            ctx.addBlock(ore.rawOreBlock());
            ctx.addBlock(ore.fillerBlock());
            final PositionalRandomFactory rng = ctx.randomState.getOrCreateRandomFactory(Identifier.withDefaultNamespace("ore"));
            if (!(rng instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory xor)) {
                ctx.unsupported = true;
                ctx.unsupportedReason = "Non-Xoroshiro OreVeinRule random factory";
                return;
            }
            ctx.oreSeedLo = xor.goldleaf$seedLo();
            ctx.oreSeedHi = xor.goldleaf$seedHi();
            ctx.hasOreVeinRules = true;
        } else {
            ctx.unsupported = true;
            ctx.unsupportedReason = "Unsupported MaterialRule: " + rule.getClass().getName();
        }
    }

    private static void collectConditionResources(final MaterialCondition cond, final CompilationContext ctx) {
        if (ctx.unsupported) {
            return;
        }
        if (cond instanceof MaterialCondition.HolderHolder hh) {
            collectConditionResources(hh.holder().value(), ctx);
        } else if (cond instanceof NotCondition not) {
            collectConditionResources(not.target(), ctx);
        } else if (cond instanceof BiomeCondition bc) {
            for (final Holder<Biome> b : bc.biomes()) {
                final var keyOpt = b.unwrapKey();
                if (keyOpt.isPresent()) {
                    ctx.addBiomeKey(keyOpt.get());
                } else {
                    ctx.unsupported = true;
                    ctx.unsupportedReason = "Unkeyed biome holder in BiomeCondition";
                    return;
                }
            }
        } else if (cond instanceof StoneDepthCondition sdc) {
            if (sdc.secondaryDepthRange() != 0) {
                ctx.usesSurfaceSecondary = true;
            }
        } else if (cond instanceof YCondition || cond instanceof WaterCondition
            || cond instanceof SteepCondition || cond instanceof HoleCondition) {
            // No extra resources
        } else if (cond instanceof AbovePreliminarySurfaceCondition) {
            ctx.usesPrelimSurface = true;
        } else if (cond instanceof NoiseThresholdCondition ntc) {
            if (ntc.is3d()) {
                ctx.getOrAddNoise3d(ntc.noise());
            } else {
                ctx.getOrAddNoise2d(ntc.noise());
            }
        } else if (cond instanceof VerticalGradientCondition vgc) {
            final PositionalRandomFactory rng = ctx.randomState.getOrCreateRandomFactory(vgc.randomName());
            if (!(rng instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory)) {
                ctx.unsupported = true;
                ctx.unsupportedReason = "Non-Xoroshiro VerticalGradientCondition random factory";
            }
        } else if (cond instanceof io.papermc.paper.world.worldgen.OptionallyFlatBedrockCondition ofb) {
            final PositionalRandomFactory rng = ctx.randomState.getOrCreateRandomFactory(ofb.randomName());
            if (!(rng instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory)) {
                ctx.unsupported = true;
                ctx.unsupportedReason = "Non-Xoroshiro OptionallyFlatBedrockCondition random factory";
            }
        } else if (cond instanceof TemperatureCondition) {
            ctx.unsupported = true;
            ctx.unsupportedReason = "TemperatureCondition not yet supported on GPU";
        } else {
            ctx.unsupported = true;
            ctx.unsupportedReason = "Unsupported MaterialCondition: " + cond.getClass().getName();
        }
    }

    private static void emitXoroshiroAndBiomeHelpers(
        final StringBuilder sb,
        final CompilationContext ctx,
        final int erodedBadlandsId,
        final int frozenOceanId,
        final int deepFrozenOceanId
    ) {
        sb.append("static inline ulong gl_rotl64(const ulong x, const int k) {\n");
        sb.append("    return (x << k) | (x >> (64 - k));\n");
        sb.append("}\n\n");

        sb.append("static inline ulong gl_mth_get_seed(const int x, const int y, const int z) {\n");
        sb.append("    long seed = (long)(x * 3129871) ^ ((long)z * 116129781L) ^ (long)y;\n");
        sb.append("    seed = seed * seed * 42317861L + seed * 11L;\n");
        sb.append("    return (ulong)(seed >> 16);\n");
        sb.append("}\n\n");

        sb.append("static inline ulong gl_xoroshiro_next(ulong* s0_p, ulong* s1_p) {\n");
        sb.append("    ulong s0 = *s0_p;\n");
        sb.append("    ulong s1 = *s1_p;\n");
        sb.append("    if ((s0 | s1) == 0UL) {\n");
        sb.append("        s0 = 0x9E3779B97F4A7C15UL;\n");
        sb.append("        s1 = 0x6A09E667F3BCC909UL;\n");
        sb.append("    }\n");
        sb.append("    ulong res = gl_rotl64(s0 + s1, 17) + s0;\n");
        sb.append("    s1 ^= s0;\n");
        sb.append("    *s0_p = gl_rotl64(s0, 49) ^ s1 ^ (s1 << 21);\n");
        sb.append("    *s1_p = gl_rotl64(s1, 28);\n");
        sb.append("    return res;\n");
        sb.append("}\n\n");

        sb.append("static inline float gl_xoroshiro_next_float(ulong* s0_p, ulong* s1_p) {\n");
        sb.append("    return (float)(gl_xoroshiro_next(s0_p, s1_p) >> 40) * 5.9604645e-8f;\n");
        sb.append("}\n\n");

        sb.append("static inline float gl_xoroshiro_next_double_f32(ulong* s0_p, ulong* s1_p) {\n");
        sb.append("    return (float)(gl_xoroshiro_next(s0_p, s1_p) >> 11) * 1.11022302e-16f;\n");
        sb.append("}\n\n");

        sb.append("static inline int gl_vertical_gradient(\n");
        sb.append("    const int bx, const int by, const int bz,\n");
        sb.append("    const int true_y, const int false_y, const float inv_span,\n");
        sb.append("    const ulong seed_lo, const ulong seed_hi)\n");
        sb.append("{\n");
        sb.append("    if (by <= true_y) return 1;\n");
        sb.append("    if (by >= false_y) return 0;\n");
        sb.append("    ulong s0 = gl_mth_get_seed(bx, by, bz) ^ seed_lo;\n");
        sb.append("    ulong s1 = seed_hi;\n");
        sb.append("    const float prob = 1.0f - (float)(by - true_y) * inv_span;\n");
        sb.append("    return gl_xoroshiro_next_float(&s0, &s1) < prob ? 1 : 0;\n");
        sb.append("}\n\n");

        sb.append("static inline long gl_lcg_next(const long seed, const long salt) {\n");
        sb.append("    return seed * (seed * 6364136223846793005L + 1442695040888963407L) + salt;\n");
        sb.append("}\n\n");

        sb.append("static inline float gl_biome_fiddle(const long rval) {\n");
        sb.append("    return (float)(((rval >> 24) & 1023L) - 512L) * (0.9f / 1024.0f);\n");
        sb.append("}\n\n");

        sb.append("static inline int gl_biome_manager_get(\n");
        sb.append("    const int x, const int y, const int z,\n");
        sb.append("    const long zoom_seed,\n");
        sb.append("    __global const uchar* restrict chunk_biomes,\n");
        sb.append("    const int min_qx, const int min_qy, const int min_qz, const int q_size_y)\n");
        sb.append("{\n");
        sb.append("    const int abs_x = x - 2;\n");
        sb.append("    const int abs_y = y - 2;\n");
        sb.append("    const int abs_z = z - 2;\n");
        sb.append("    const int parent_x = abs_x >> 2;\n");
        sb.append("    const int parent_y = abs_y >> 2;\n");
        sb.append("    const int parent_z = abs_z >> 2;\n");
        sb.append("    const float fx = (float)(abs_x & 3) * 0.25f;\n");
        sb.append("    const float fy = (float)(abs_y & 3) * 0.25f;\n");
        sb.append("    const float fz = (float)(abs_z & 3) * 0.25f;\n");
        sb.append("    int min_i = 0;\n");
        sb.append("    float min_dist = 1.0e30f;\n");
        sb.append("    for (int i = 0; i < 8; i++) {\n");
        sb.append("        const int xe = (i & 4) == 0;\n");
        sb.append("        const int ye = (i & 2) == 0;\n");
        sb.append("        const int ze = (i & 1) == 0;\n");
        sb.append("        const int cx = xe ? parent_x : (parent_x + 1);\n");
        sb.append("        const int cy = ye ? parent_y : (parent_y + 1);\n");
        sb.append("        const int cz = ze ? parent_z : (parent_z + 1);\n");
        sb.append("        const float dx = xe ? fx : (fx - 1.0f);\n");
        sb.append("        const float dy = ye ? fy : (fy - 1.0f);\n");
        sb.append("        const float dz = ze ? fz : (fz - 1.0f);\n");
        sb.append("        long rv = zoom_seed;\n");
        sb.append("        rv = gl_lcg_next(rv, (long)cx);\n");
        sb.append("        rv = gl_lcg_next(rv, (long)cy);\n");
        sb.append("        rv = gl_lcg_next(rv, (long)cz);\n");
        sb.append("        rv = gl_lcg_next(rv, (long)cx);\n");
        sb.append("        rv = gl_lcg_next(rv, (long)cy);\n");
        sb.append("        rv = gl_lcg_next(rv, (long)cz);\n");
        sb.append("        const float fidx = gl_biome_fiddle(rv); rv = gl_lcg_next(rv, zoom_seed);\n");
        sb.append("        const float fidy = gl_biome_fiddle(rv); rv = gl_lcg_next(rv, zoom_seed);\n");
        sb.append("        const float fidz = gl_biome_fiddle(rv);\n");
        sb.append("        const float sx = dx + fidx, sy = dy + fidy, sz = dz + fidz;\n");
        sb.append("        const float d = sz * sz + sy * sy + sx * sx;\n");
        sb.append("        if (d < min_dist) { min_dist = d; min_i = i; }\n");
        sb.append("    }\n");
        sb.append("    int qx = ((min_i & 4) == 0 ? parent_x : (parent_x + 1)) - min_qx + 1;\n");
        sb.append("    int qy = ((min_i & 2) == 0 ? parent_y : (parent_y + 1)) - min_qy;\n");
        sb.append("    int qz = ((min_i & 1) == 0 ? parent_z : (parent_z + 1)) - min_qz + 1;\n");
        sb.append("    qx = clamp(qx, 0, 5);\n");
        sb.append("    qz = clamp(qz, 0, 5);\n");
        sb.append("    qy = clamp(qy, 0, q_size_y - 1);\n");
        sb.append("    return (int)chunk_biomes[(qx + qz * 6) * q_size_y + qy];\n");
        sb.append("}\n\n");

        sb.append("static inline int gl_biome_in_mask(\n");
        sb.append("    int* biome_id_p,\n");
        sb.append("    const int x, const int y, const int z,\n");
        sb.append("    const long zoom_seed,\n");
        sb.append("    __global const uchar* restrict chunk_biomes,\n");
        sb.append("    const int min_qx, const int min_qy, const int min_qz, const int q_size_y,\n");
        sb.append("    const ulong m0, const ulong m1, const ulong m2, const ulong m3)\n");
        sb.append("{\n");
        sb.append("    int b = *biome_id_p;\n");
        sb.append("    if (b < 0) {\n");
        sb.append("        b = gl_biome_manager_get(x, y, z, zoom_seed, chunk_biomes, min_qx, min_qy, min_qz, q_size_y);\n");
        sb.append("        *biome_id_p = b;\n");
        sb.append("    }\n");
        sb.append("    if (b < 64) return (int)((m0 >> b) & 1UL);\n");
        sb.append("    if (b < 128) return (int)((m1 >> (b - 64)) & 1UL);\n");
        sb.append("    if (b < 192) return (int)((m2 >> (b - 128)) & 1UL);\n");
        sb.append("    if (b < 254) return (int)((m3 >> (b - 192)) & 1UL);\n");
        sb.append("    return 0;\n");
        sb.append("}\n\n");

        if (ctx.usesClayBands) {
            final BlockState[] bands = ctx.materialSystem.goldleaf$clayBands();
            sb.append("__constant uchar gl_clay_bands[64] = {");
            for (int i = 0; i < 64; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                final int id = ctx.blockToId.getOrDefault(bands[i % bands.length], 1);
                sb.append(id);
            }
            sb.append("};\n\n");
        }
        sb.append("#define GL_BIOME_ERODED_BADLANDS ").append(erodedBadlandsId).append('\n');
        sb.append("#define GL_BIOME_FROZEN_OCEAN ").append(frozenOceanId).append('\n');
        sb.append("#define GL_BIOME_DEEP_FROZEN_OCEAN ").append(deepFrozenOceanId).append("\n\n");

        if (ctx.icebergSurfaceAuxIdx >= 0) {
            emitFrozenOceanSimplexHelpers(sb);
        }
        if (ctx.hasOreVeinRules) {
            sb.append("static inline float gl_ore_trilinear(__local const float* grid, const int lx, const int by, const int lz) {\n");
            sb.append("    const int rel_y = by + 64;\n");
            sb.append("    const int cx = lx >> 2;\n");
            sb.append("    const int cy = rel_y >> 3;\n");
            sb.append("    const float fx = (float)(lx & 3) * 0.25f;\n");
            sb.append("    const float fz = (float)(lz & 3) * 0.25f;\n");
            sb.append("    const float fy = (float)(rel_y & 7) * 0.125f;\n");
            sb.append("    const int b0 = cy * 10 + cx;\n");
            sb.append("    const int b1 = b0 + 10;\n");
            sb.append("    const float x00 = grid[b0] + fx * (grid[b0 + 1] - grid[b0]);\n");
            sb.append("    const float x10 = grid[b1] + fx * (grid[b1 + 1] - grid[b1]);\n");
            sb.append("    const float x01 = grid[b0 + 5] + fx * (grid[b0 + 6] - grid[b0 + 5]);\n");
            sb.append("    const float x11 = grid[b1 + 5] + fx * (grid[b1 + 6] - grid[b1 + 5]);\n");
            sb.append("    const float y0 = x00 + fy * (x10 - x00);\n");
            sb.append("    const float y1 = x01 + fy * (x11 - x01);\n");
            sb.append("    return y0 + fz * (y1 - y0);\n");
            sb.append("}\n\n");
        }
    }

    private static void emitFrozenOceanSimplexHelpers(final StringBuilder sb) {
        final net.minecraft.world.level.levelgen.WorldgenRandom frozenRng =
            new net.minecraft.world.level.levelgen.WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(3456L));
        emitSimplexPermTable(sb, "gl_simplex_f0_perm", buildSimplexPerms(frozenRng));
        emitSimplexPermTable(sb, "gl_simplex_f1_perm", buildSimplexPerms(frozenRng));
        emitSimplexPermTable(sb, "gl_simplex_f2_perm", buildSimplexPerms(frozenRng));
        final net.minecraft.world.level.levelgen.WorldgenRandom infoRng =
            new net.minecraft.world.level.levelgen.WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(2345L));
        emitSimplexPermTable(sb, "gl_simplex_info_perm", buildSimplexPerms(infoRng));
        sb.append("__constant float gl_s2_gx[12] = {1.0f,-1.0f,1.0f,-1.0f,1.0f,-1.0f,1.0f,-1.0f,0.0f,0.0f,0.0f,0.0f};\n");
        sb.append("__constant float gl_s2_gy[12] = {1.0f,1.0f,-1.0f,-1.0f,0.0f,0.0f,0.0f,0.0f,1.0f,-1.0f,1.0f,-1.0f};\n");
        sb.append("static inline float gl_simplex_2d(__constant const uchar* perm, const float xin, const float yin) {\n");
        sb.append("    const float F2 = 0.3660254037844386f;\n");
        sb.append("    const float G2 = 0.21132486540518713f;\n");
        sb.append("    const float s = (xin + yin) * F2;\n");
        sb.append("    const int i = (int)floor(xin + s);\n");
        sb.append("    const int j = (int)floor(yin + s);\n");
        sb.append("    const float t = (float)(i + j) * G2;\n");
        sb.append("    const float x0 = xin - ((float)i - t);\n");
        sb.append("    const float y0 = yin - ((float)j - t);\n");
        sb.append("    const int i1 = (x0 > y0) ? 1 : 0;\n");
        sb.append("    const int j1 = (x0 > y0) ? 0 : 1;\n");
        sb.append("    const float x1 = x0 - (float)i1 + G2;\n");
        sb.append("    const float y1 = y0 - (float)j1 + G2;\n");
        sb.append("    const float x2 = x0 - 1.0f + 2.0f * G2;\n");
        sb.append("    const float y2 = y0 - 1.0f + 2.0f * G2;\n");
        sb.append("    const int ii = i & 255;\n");
        sb.append("    const int jj = j & 255;\n");
        sb.append("    const int gi0 = (int)perm[(ii + (int)perm[jj]) & 255] % 12;\n");
        sb.append("    const int gi1 = (int)perm[(ii + i1 + (int)perm[(jj + j1) & 255]) & 255] % 12;\n");
        sb.append("    const int gi2 = (int)perm[(ii + 1 + (int)perm[(jj + 1) & 255]) & 255] % 12;\n");
        sb.append("    float n = 0.0f;\n");
        sb.append("    float t0 = 0.5f - x0 * x0 - y0 * y0;\n");
        sb.append("    if (t0 > 0.0f) { t0 *= t0; n += t0 * t0 * (gl_s2_gx[gi0] * x0 + gl_s2_gy[gi0] * y0); }\n");
        sb.append("    float t1 = 0.5f - x1 * x1 - y1 * y1;\n");
        sb.append("    if (t1 > 0.0f) { t1 *= t1; n += t1 * t1 * (gl_s2_gx[gi1] * x1 + gl_s2_gy[gi1] * y1); }\n");
        sb.append("    float t2 = 0.5f - x2 * x2 - y2 * y2;\n");
        sb.append("    if (t2 > 0.0f) { t2 *= t2; n += t2 * t2 * (gl_s2_gx[gi2] * x2 + gl_s2_gy[gi2] * y2); }\n");
        sb.append("    return 70.0f * n;\n");
        sb.append("}\n\n");
        sb.append("static inline int gl_frozen_ocean_should_melt(const int bx, const int bz) {\n");
        sb.append("    const float fx = (float)bx * 0.05f;\n");
        sb.append("    const float fz = (float)bz * 0.05f;\n");
        sb.append("    const float fnoise = gl_simplex_2d(gl_simplex_f0_perm, fx, fz) * 0.14285715f\n");
        sb.append("        + gl_simplex_2d(gl_simplex_f1_perm, fx * 0.5f, fz * 0.5f) * 0.2857143f\n");
        sb.append("        + gl_simplex_2d(gl_simplex_f2_perm, fx * 0.25f, fz * 0.25f) * 0.5714286f;\n");
        sb.append("    const float edge = gl_simplex_2d(gl_simplex_info_perm, (float)bx * 0.2f, (float)bz * 0.2f);\n");
        sb.append("    if (fnoise * 7.0f + edge < 0.3f) {\n");
        sb.append("        const float small = gl_simplex_2d(gl_simplex_info_perm, (float)bx * 0.09f, (float)bz * 0.09f);\n");
        sb.append("        if (small < 0.8f) return 1;\n");
        sb.append("    }\n");
        sb.append("    return 0;\n");
        sb.append("}\n\n");
    }

    private static byte[] buildSimplexPerms(final net.minecraft.util.RandomSource random) {
        // Mirror GradientNoise(random, 0.0) constructor RNG consumption
        random.nextDouble();
        random.nextDouble();
        random.nextDouble();
        final byte[] perms = new byte[256];
        for (int i = 0; i < 256; i++) {
            perms[i] = (byte) i;
        }
        for (int i = 0; i < 256; i++) {
            final int offset = random.nextInt(256 - i);
            final byte tmp = perms[i];
            perms[i] = perms[offset + i];
            perms[offset + i] = tmp;
        }
        return perms;
    }

    private static void emitSimplexPermTable(final StringBuilder sb, final String name, final byte[] perms) {
        sb.append("__constant uchar ").append(name).append("[256] = {");
        for (int i = 0; i < 256; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(perms[i] & 0xFF);
        }
        sb.append("};\n");
    }

    private static void emitMaterialRuleEvaluator(
        final StringBuilder sb,
        final MaterialRule rootRule,
        final CompilationContext ctx,
        final List<String> auxCalls
    ) {
        sb.append("static int gl_eval_material_rule(\n");
        sb.append("    const int bx, const int by, const int bz,\n");
        sb.append("    const long biome_zoom_seed,\n");
        sb.append("    __global const uchar* restrict chunk_biomes,\n");
        sb.append("    const int min_qx, const int min_qy, const int min_qz, const int q_size_y,\n");
        sb.append("    const int surface_depth,\n");
        sb.append("    const float surface_secondary,\n");
        sb.append("    const int min_surface_level,\n");
        sb.append("    const int grad_x, const int grad_z,\n");
        sb.append("    const int stone_above, const int stone_below,\n");
        sb.append("    const int water_height,\n");
        sb.append("    const float clay_offset,\n");
        if (ctx.hasOreVeinRules) {
            sb.append("    const int lx, const int lz,\n");
            sb.append("    const int ore_has_vein,\n");
            sb.append("    __local const float* ore_toggle,\n");
            sb.append("    __local const float* ore_vein_a,\n");
            sb.append("    __local const float* ore_vein_b,\n");
        }
        if (!ctx.noise2dToAuxIdx.isEmpty()) {
            sb.append("    const float* restrict n2d_cache,\n");
        }
        sb.append("    __global const int* restrict gl_lut_spill)\n");
        sb.append("{\n");
        sb.append("    int biome_id = -1;\n");
        emitRuleBody(sb, rootRule, ctx, auxCalls, "    ");
        sb.append("    return -1;\n");
        sb.append("}\n\n");
    }

    private static void emitRuleBody(
        final StringBuilder sb,
        final MaterialRule rule,
        final CompilationContext ctx,
        final List<String> auxCalls,
        final String indent
    ) {
        if (rule instanceof MaterialRule.HolderHolder hh) {
            emitRuleBody(sb, hh.holder().value(), ctx, auxCalls, indent);
        } else if (rule instanceof SequenceRule seq) {
            for (final MaterialRule child : seq.sequence()) {
                emitRuleBody(sb, child, ctx, auxCalls, indent);
            }
        } else if (rule instanceof ConditionRule cond) {
            final String condExpr = emitConditionExpr(cond.ifTrue(), ctx, auxCalls);
            sb.append(indent).append("if (").append(condExpr).append(") {\n");
            emitRuleBody(sb, cond.thenRun(), ctx, auxCalls, indent + "    ");
            sb.append(indent).append("}\n");
        } else if (rule instanceof BlockRule blk) {
            final int id = ctx.blockToId.get(blk.resultState());
            sb.append(indent).append("return ").append(id).append(";\n");
        } else if (rule instanceof BandlandsRule) {
            sb.append(indent).append("return (int)gl_clay_bands[(by + (int)floor(clay_offset * 4.0f + 0.5f)) & 63];\n");
        } else if (rule instanceof OreVeinRule ore) {
            final int[] yBounds = tryExtractYBounds(ore.density());
            final int minY = yBounds != null ? yBounds[0] : -60;
            final int maxY = yBounds != null ? yBounds[1] : 50;
            final boolean whenPos = minY >= 0;
            final int oreId = ctx.blockToId.get(ore.oreBlock());
            final int rawOreId = ctx.blockToId.get(ore.rawOreBlock());
            final int fillerId = ctx.blockToId.get(ore.fillerBlock());
            sb.append(indent).append("if (ore_has_vein != 0 && by >= ").append(minY).append(" && by < ").append(maxY).append(") {\n");
            sb.append(indent).append("    const float vt = gl_ore_trilinear(ore_toggle, lx, by, lz);\n");
            sb.append(indent).append("    const float v_signed = ").append(whenPos ? "vt" : "-vt").append(";\n");
            sb.append(indent).append("    if (v_signed >= 0.4f) {\n");
            sb.append(indent).append("        const float dist_edge = (float)min(").append(maxY).append(" - by, by - ").append(minY).append(");\n");
            sb.append(indent).append("        const float roundoff = clamp(dist_edge * 0.05f, 0.0f, 1.0f) * 0.2f - 0.2f;\n");
            sb.append(indent).append("        if (v_signed - 0.4f + roundoff >= 0.0f) {\n");
            sb.append(indent).append("            const float va = fabs(gl_ore_trilinear(ore_vein_a, lx, by, lz));\n");
            sb.append(indent).append("            const float vb = fabs(gl_ore_trilinear(ore_vein_b, lx, by, lz));\n");
            sb.append(indent).append("            if (0.08f - fmax(va, vb) >= 0.0f) {\n");
            sb.append(indent).append("                ulong os0 = gl_mth_get_seed(bx, by, bz) ^ ").append(toUnsignedHex(ctx.oreSeedLo)).append(";\n");
            sb.append(indent).append("                ulong os1 = ").append(toUnsignedHex(ctx.oreSeedHi)).append(";\n");
            sb.append(indent).append("                if (gl_xoroshiro_next_float(&os0, &os1) <= 0.7f) {\n");
            sb.append(indent).append("                    const float rich = 0.1f + clamp((fabs(vt) - 0.4f) * 5.0f, 0.0f, 1.0f) * 0.2f;\n");
            sb.append(indent).append("                    if (gl_xoroshiro_next_float(&os0, &os1) < rich && (-0.3f - (").append(auxCalls.get(ctx.oreGapAuxIdx)).append(")) < 0.0f) {\n");
            sb.append(indent).append("                        return (gl_xoroshiro_next_float(&os0, &os1) < ").append(ore.rawOreChance()).append("f) ? ").append(rawOreId).append(" : ").append(oreId).append(";\n");
            sb.append(indent).append("                    }\n");
            sb.append(indent).append("                    return ").append(fillerId).append(";\n");
            sb.append(indent).append("                }\n");
            sb.append(indent).append("            }\n");
            sb.append(indent).append("        }\n");
            sb.append(indent).append("    }\n");
            sb.append(indent).append("}\n");
        }
    }

    private static int @Nullable [] tryExtractYBounds(final DensityFunction fn) {
        if (fn instanceof RangeChoiceFunction rc) {
            final float lo = rc.minInclusive();
            final float hi = rc.maxExclusive();
            if (lo >= -256.0f && hi <= 512.0f && lo < hi) {
                return new int[] {(int) Math.floor(lo), (int) Math.ceil(hi)};
            }
        }
        return null;
    }

    private static String emitConditionExpr(
        final MaterialCondition cond,
        final CompilationContext ctx,
        final List<String> auxCalls
    ) {
        if (cond instanceof MaterialCondition.HolderHolder hh) {
            return emitConditionExpr(hh.holder().value(), ctx, auxCalls);
        }
        if (cond instanceof NotCondition not) {
            return "(!(" + emitConditionExpr(not.target(), ctx, auxCalls) + "))";
        }
        if (cond instanceof BiomeCondition bc) {
            long m0 = 0L, m1 = 0L, m2 = 0L, m3 = 0L;
            for (final Holder<Biome> b : bc.biomes()) {
                final int id = ctx.biomeKeyToId.get(b.unwrapKey().orElseThrow());
                if (id < 64) {
                    m0 |= (1L << id);
                } else if (id < 128) {
                    m1 |= (1L << (id - 64));
                } else if (id < 192) {
                    m2 |= (1L << (id - 128));
                } else {
                    m3 |= (1L << (id - 192));
                }
            }
            return "gl_biome_in_mask(&biome_id, bx, by, bz, biome_zoom_seed, chunk_biomes, min_qx, min_qy, min_qz, q_size_y, "
                + toUnsignedHex(m0) + ", " + toUnsignedHex(m1) + ", " + toUnsignedHex(m2) + ", " + toUnsignedHex(m3) + ")";
        }
        if (cond instanceof StoneDepthCondition sdc) {
            final String depthVar = sdc.surfaceType() == CaveSurface.CEILING ? "stone_below" : "stone_above";
            final StringBuilder e = new StringBuilder();
            e.append("(").append(depthVar).append(" <= (").append(1 + sdc.offset());
            if (sdc.addSurfaceDepth()) {
                e.append(" + surface_depth");
            }
            if (sdc.secondaryDepthRange() != 0) {
                e.append(" + (int)((surface_secondary + 1.0f) * ").append(0.5f * sdc.secondaryDepthRange()).append("f)");
            }
            e.append("))");
            return e.toString();
        }
        if (cond instanceof YCondition yc) {
            final int anchorY = yc.anchor().resolveY(ctx.worldGenContext);
            final String lhs = yc.addStoneDepth() ? "(by + stone_above)" : "by";
            final String rhs = yc.surfaceDepthMultiplier() == 0
                ? Integer.toString(anchorY)
                : "(" + anchorY + " + surface_depth * " + yc.surfaceDepthMultiplier() + ")";
            return "(" + lhs + " >= " + rhs + ")";
        }
        if (cond instanceof WaterCondition wc) {
            final String lhs = wc.addStoneDepth() ? "(by + stone_above)" : "by";
            final String rhs = "(water_height + " + wc.offset()
                + (wc.surfaceDepthMultiplier() == 0 ? "" : " + surface_depth * " + wc.surfaceDepthMultiplier()) + ")";
            return "(water_height == -2147483648 || " + lhs + " >= " + rhs + ")";
        }
        if (cond instanceof SteepCondition) {
            return "(grad_x <= -4 || grad_z >= 4)";
        }
        if (cond instanceof HoleCondition) {
            return "(surface_depth <= 0)";
        }
        if (cond instanceof AbovePreliminarySurfaceCondition) {
            return "(by >= min_surface_level)";
        }
        if (cond instanceof NoiseThresholdCondition ntc) {
            final String valExpr;
            if (ntc.is3d()) {
                final int auxIdx = ctx.noise3dToAuxIdx.get(ntc.noise());
                valExpr = "(" + auxCalls.get(auxIdx) + ")";
            } else {
                int cacheSlot = 0;
                for (final ResourceKey<NormalNoise> k : ctx.noise2dToAuxIdx.keySet()) {
                    if (k.equals(ntc.noise())) {
                        break;
                    }
                    cacheSlot++;
                }
                valExpr = "n2d_cache[" + cacheSlot + "]";
            }
            final String lo = formatFloatLiteral(ntc.minThreshold());
            final String hi = formatFloatLiteral(ntc.maxThreshold());
            return "(" + valExpr + " >= " + lo + " && " + valExpr + " <= " + hi + ")";
        }
        if (cond instanceof VerticalGradientCondition vgc) {
            final int trueY = vgc.trueAtAndBelow().resolveY(ctx.worldGenContext);
            final int falseY = vgc.falseAtAndAbove().resolveY(ctx.worldGenContext);
            final XoroshiroRandomSource.XoroshiroPositionalRandomFactory rng =
                (XoroshiroRandomSource.XoroshiroPositionalRandomFactory) ctx.randomState.getOrCreateRandomFactory(vgc.randomName());
            final float invSpan = 1.0f / (float) (falseY - trueY);
            return "gl_vertical_gradient(bx, by, bz, " + trueY + ", " + falseY + ", "
                + formatFloatLiteral(invSpan) + ", " + toUnsignedHex(rng.goldleaf$seedLo()) + ", "
                + toUnsignedHex(rng.goldleaf$seedHi()) + ")";
        }
        if (cond instanceof io.papermc.paper.world.worldgen.OptionallyFlatBedrockCondition ofb) {
            final boolean hasFlatBedrock = ctx.generateFlatBedrock;
            final int tempTrueY = ofb.trueAtAndBelow().resolveY(ctx.worldGenContext);
            final int tempFalseY = ofb.falseAtAndAbove().resolveY(ctx.worldGenContext);
            final int flatY = ofb.isRoof() ? Math.max(tempFalseY, tempTrueY) - 1 : Math.min(tempFalseY, tempTrueY);
            final int trueY = hasFlatBedrock ? flatY : tempTrueY;
            final int falseY = hasFlatBedrock ? flatY : tempFalseY;
            if (trueY == falseY) {
                return "(by <= " + trueY + ")";
            }
            final XoroshiroRandomSource.XoroshiroPositionalRandomFactory rng =
                (XoroshiroRandomSource.XoroshiroPositionalRandomFactory) ctx.randomState.getOrCreateRandomFactory(ofb.randomName());
            final float invSpan = 1.0f / (float) (falseY - trueY);
            return "gl_vertical_gradient(bx, by, bz, " + trueY + ", " + falseY + ", "
                + formatFloatLiteral(invSpan) + ", " + toUnsignedHex(rng.goldleaf$seedLo()) + ", "
                + toUnsignedHex(rng.goldleaf$seedHi()) + ")";
        }
        return "0";
    }

    private static void emitTerrainU8Kernel(
        final StringBuilder sb,
        final String primaryPrefix,
        final OpenClBackendEmitter.CoarseInterpSpec coarseSpec,
        final String combinerCallExpr
    ) {
        final int cellXz = coarseSpec.cellSizeXz();
        final int cellY = coarseSpec.cellSizeY();
        final int gXz = coarseSpec.gridSizeXz();
        final int gY = coarseSpec.gridSizeY();
        final int nCh = coarseSpec.coarseFunctionNames().size();
        final int coarseTotal = gXz * gY * gXz * nCh;

        sb.append("static inline float gl_bury_contrib(const float dx, const float dy, const float dz) {\n");
        sb.append("    const float d2 = dx * dx + dy * dy + dz * dz;\n");
        sb.append("    return d2 >= 36.0f ? 0.0f : (1.0f - sqrt(d2) * 0.16666667f);\n");
        sb.append("}\n\n");
        sb.append("static inline float gl_beard_contrib(const int dx, const int dy, const int dz, const int y_to_ground) {\n");
        sb.append("    if (dx < -12 || dx >= 12 || dy < -12 || dy >= 12 || dz < -12 || dz >= 12) return 0.0f;\n");
        sb.append("    const float dy_off = (float)y_to_ground + 0.5f;\n");
        sb.append("    const float d2 = (float)(dx * dx + dz * dz) + dy_off * dy_off;\n");
        sb.append("    const float val = -dy_off * rsqrt(d2 * 0.5f) * 0.5f;\n");
        sb.append("    const float dyk = (float)dy + 0.5f;\n");
        sb.append("    const float k = exp(-((float)(dx * dx + dz * dz) + dyk * dyk) * 0.0625f);\n");
        sb.append("    return val * k;\n");
        sb.append("}\n\n");
        sb.append("__kernel void ").append(TERRAIN_KERNEL_NAME).append("(\n");
        sb.append("    __global uchar* restrict out_voxels,\n");
        sb.append("    __global const int4* restrict batch_origins,\n");
        sb.append("    const int batch_count,\n");
        sb.append("    const int section_count,\n");
        sb.append("    const int sea_level,\n");
        sb.append("    const int deep_fluid_y,\n");
        sb.append("    const int min_block_y,\n");
        sb.append("    __global const int2* restrict batch_beard_meta,\n");
        sb.append("    __global const int8* restrict batch_beard_items,\n");
        sb.append("    __global const int* restrict gl_lut_spill,\n");
        sb.append("    const int use_aquifer)\n");
        sb.append("{\n");
        sb.append("    const int sec_gid = (int)get_group_id(0);\n");
        sb.append("    const int tid = (int)get_local_id(0);\n");
        sb.append("    const int total_sections = batch_count * section_count;\n");
        sb.append("    if (sec_gid >= total_sections) {\n");
        sb.append("        return;\n");
        sb.append("    }\n");
        sb.append("    const int batch_idx = sec_gid / section_count;\n");
        sb.append("    const int s = sec_gid - batch_idx * section_count;\n");
        sb.append("    const int4 orig = batch_origins[batch_idx];\n");
        sb.append("    const int sec_min_y = min_block_y + (s << 4);\n");
        sb.append("    const int lx = tid & 15;\n");
        sb.append("    const int lz = tid >> 4;\n");
        sb.append("    const int bx = orig.x + lx;\n");
        sb.append("    const int bz = orig.z + lz;\n");
        sb.append("    __local float coarse_vals[").append(coarseTotal).append("];\n");
        sb.append("    for (int idx = tid; idx < ").append(coarseTotal).append("; idx += 256) {\n");
        sb.append("        int rem = idx;\n");
        sb.append("        const int ch = rem % ").append(nCh).append("; rem /= ").append(nCh).append(";\n");
        sb.append("        const int cx = rem % ").append(gXz).append("; rem /= ").append(gXz).append(";\n");
        sb.append("        const int cz = rem % ").append(gXz).append("; rem /= ").append(gXz).append(";\n");
        sb.append("        const int cy = rem;\n");
        sb.append("        const int cbx = orig.x + cx * ").append(cellXz).append(";\n");
        sb.append("        const int cby = sec_min_y + cy * ").append(cellY).append(";\n");
        sb.append("        const int cbz = orig.z + cz * ").append(cellXz).append(";\n");
        sb.append("        float cv = 0.0f;\n");
        for (int c = 0; c < nCh; c++) {
            final String fnName = coarseSpec.coarseFunctionNames().get(c);
            final boolean isQ32 = primaryPrefix.contains("long " + fnName + "(");
            final boolean acceptsSpill = primaryPrefix.contains(fnName + "(int ") && primaryPrefix.contains("gl_lut_spill");
            final String call = acceptsSpill
                ? fnName + "(cbx, cby, cbz, gl_lut_spill)"
                : fnName + "(cbx, cby, cbz)";
            sb.append(c == 0 ? "        if (ch == 0) { cv = " : "        else if (ch == " + c + ") { cv = ");
            if (isQ32) {
                sb.append("q32_to_f32(").append(call).append("); }\n");
            } else {
                sb.append("(float)(").append(call).append("); }\n");
            }
        }
        sb.append("        coarse_vals[idx] = cv;\n");
        sb.append("    }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("    const int cx0 = lx / ").append(cellXz).append(";\n");
        sb.append("    const int cz0 = lz / ").append(cellXz).append(";\n");
        sb.append("    const float tx = (float)(lx - cx0 * ").append(cellXz).append(") * ").append(1.0f / cellXz).append("f;\n");
        sb.append("    const float tz = (float)(lz - cz0 * ").append(cellXz).append(") * ").append(1.0f / cellXz).append("f;\n");
        sb.append("    const int sec_voxel_base = sec_gid * 4096;\n");
        sb.append("    const int2 bmeta = batch_beard_meta[batch_idx];\n");
        sb.append("    const int beard_off = bmeta.x;\n");
        sb.append("    const int beard_cnt = bmeta.y;\n");
        sb.append("    int sec_has_beard = 0;\n");
        sb.append("    if (beard_cnt > 0) {\n");
        sb.append("        for (int bi = 0; bi < beard_cnt; bi++) {\n");
        sb.append("            const int8 it = batch_beard_items[beard_off + bi];\n");
        sb.append("            const int imin_y = (it.s7 == 5) ? (it.s1 - 12) : (min(it.s1, it.s6) - 12);\n");
        sb.append("            const int imax_y = (it.s7 == 5) ? (it.s1 + 12) : (max(it.s4, it.s6) + 12);\n");
        sb.append("            if (sec_min_y + 15 >= imin_y && sec_min_y <= imax_y) { sec_has_beard = 1; break; }\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    for (int ly = 0; ly < 16; ly++) {\n");
        sb.append("        const int by = sec_min_y + ly;\n");
        sb.append("        const int cy0 = ly / ").append(cellY).append(";\n");
        sb.append("        const float ty = (float)(ly - cy0 * ").append(cellY).append(") * ").append(1.0f / cellY).append("f;\n");
        for (int c = 0; c < nCh; c++) {
            sb.append("        float interp_").append(c).append(";\n");
            sb.append("        {\n");
            sb.append("            const int b000 = (((cy0 * ").append(gXz).append(" + cz0) * ").append(gXz).append(" + cx0) * ").append(nCh).append(" + ").append(c).append(");\n");
            sb.append("            const int b100 = b000 + ").append(nCh).append(";\n");
            sb.append("            const int b001 = b000 + ").append(gXz * nCh).append(";\n");
            sb.append("            const int b101 = b001 + ").append(nCh).append(";\n");
            sb.append("            const int b010 = b000 + ").append(gXz * gXz * nCh).append(";\n");
            sb.append("            const int b110 = b010 + ").append(nCh).append(";\n");
            sb.append("            const int b011 = b010 + ").append(gXz * nCh).append(";\n");
            sb.append("            const int b111 = b011 + ").append(nCh).append(";\n");
            sb.append("            const float y00 = coarse_vals[b000] + ty * (coarse_vals[b010] - coarse_vals[b000]);\n");
            sb.append("            const float y10 = coarse_vals[b100] + ty * (coarse_vals[b110] - coarse_vals[b100]);\n");
            sb.append("            const float y01 = coarse_vals[b001] + ty * (coarse_vals[b011] - coarse_vals[b001]);\n");
            sb.append("            const float y11 = coarse_vals[b101] + ty * (coarse_vals[b111] - coarse_vals[b101]);\n");
            sb.append("            const float x0 = y00 + tx * (y10 - y00);\n");
            sb.append("            const float x1 = y01 + tx * (y11 - y01);\n");
            sb.append("            interp_").append(c).append(" = x0 + tz * (x1 - x0);\n");
            sb.append("        }\n");
        }
        sb.append("        float d = ").append(combinerCallExpr).append(";\n");
        sb.append("        if (sec_has_beard != 0) {\n");
        sb.append("            for (int bi = 0; bi < beard_cnt; bi++) {\n");
        sb.append("                const int8 it = batch_beard_items[beard_off + bi];\n");
        sb.append("                const int mode = it.s7;\n");
        sb.append("                if (mode == 5) {\n");
        sb.append("                    const int jdx = bx - it.s0;\n");
        sb.append("                    const int jdy = by - it.s1;\n");
        sb.append("                    const int jdz = bz - it.s2;\n");
        sb.append("                    d += gl_beard_contrib(jdx, jdy, jdz, jdy) * 0.4f;\n");
        sb.append("                } else {\n");
        sb.append("                    const int bdx = max(0, max(it.s0 - bx, bx - it.s3));\n");
        sb.append("                    const int bdz = max(0, max(it.s2 - bz, bz - it.s5));\n");
        sb.append("                    const int ground_y = it.s6;\n");
        sb.append("                    const int dy_g = by - ground_y;\n");
        sb.append("                    if (mode == 1) {\n");
        sb.append("                        d += gl_bury_contrib((float)bdx, (float)dy_g * 0.5f, (float)bdz);\n");
        sb.append("                    } else if (mode == 2) {\n");
        sb.append("                        d += gl_beard_contrib(bdx, dy_g, bdz, dy_g) * 0.8f;\n");
        sb.append("                    } else if (mode == 3) {\n");
        sb.append("                        const int bdy = max(0, max(ground_y - by, by - it.s4));\n");
        sb.append("                        d += gl_beard_contrib(bdx, bdy, bdz, dy_g) * 0.8f;\n");
        sb.append("                    } else if (mode == 4) {\n");
        sb.append("                        const int bdy = max(0, max(it.s1 - by, by - it.s4));\n");
        sb.append("                        d += gl_bury_contrib((float)bdx * 0.5f, (float)bdy * 0.5f, (float)bdz * 0.5f) * 0.8f;\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        uchar code = 0;\n");
        sb.append("        if (d > 0.0f) {\n");
        sb.append("            code = 1;\n");
        sb.append("        } else if (by < deep_fluid_y) {\n");
        sb.append("            code = 3;\n");
        sb.append("        } else if (use_aquifer != 0) {\n");
        sb.append("            const float nd = -d;\n");
        sb.append("            int qd = (nd < 1.0f) ? (int)(nd * 64.0f + 0.5f) : (64 + (int)((nd - 1.0f) * 8.0f + 0.5f));\n");
        sb.append("            if (qd < 0) qd = 0;\n");
        sb.append("            if (qd > 127) qd = 127;\n");
        sb.append("            code = (uchar)(128 + qd);\n");
        sb.append("        } else if (by < sea_level) {\n");
        sb.append("            code = 2;\n");
        sb.append("        }\n");
        sb.append("        out_voxels[sec_voxel_base + ((ly << 8) | (lz << 4) | lx)] = code;\n");
        sb.append("    }\n");
        sb.append("}\n");
    }

    private static void emitSurfaceApplyKernel(
        final StringBuilder sb,
        final CompilationContext ctx,
        final List<String> auxCalls,
        final long noiseSeedLo,
        final long noiseSeedHi
    ) {
        sb.append("__kernel void ").append(SURFACE_KERNEL_NAME).append("(\n");
        sb.append("    __global uchar* restrict out_voxels,\n");
        sb.append("    __global ushort* restrict out_meta,\n");
        sb.append("    __global uchar* restrict out_chunk_flags,\n");
        sb.append("    __global const int4* restrict batch_origins,\n");
        sb.append("    __global const uchar* restrict batch_biomes_halo,\n");
        sb.append("    __global const float4* restrict batch_prelim_corners,\n");
        sb.append("    const int batch_count,\n");
        sb.append("    const int section_count,\n");
        sb.append("    const int min_block_y,\n");
        sb.append("    const long biome_zoom_seed,\n");
        sb.append("    const int do_pack,\n");
        sb.append("    __global const int* restrict gl_lut_spill,\n");
        sb.append("    __global ushort* restrict out_heightmaps)\n");
        sb.append("{\n");
        sb.append("    const int wg_id = (int)get_group_id(0);\n");
        sb.append("    const int batch_idx = wg_id >> 2;\n");
        sb.append("    const int strip = wg_id & 3;\n");
        sb.append("    const int tid = (int)get_local_id(0);\n");
        sb.append("    if (batch_idx >= batch_count) {\n");
        sb.append("        return;\n");
        sb.append("    }\n");
        sb.append("    const int4 orig = batch_origins[batch_idx];\n");
        sb.append("    const int lx = tid & 15;\n");
        sb.append("    const int local_lz = tid >> 4;\n");
        sb.append("    const int lz = (strip << 2) + local_lz;\n");
        sb.append("    const int col_idx = (lz << 4) | lx;\n");
        sb.append("    const int bx = orig.x + lx;\n");
        sb.append("    const int bz = orig.z + lz;\n");
        sb.append("    const int chunk_base_sec = batch_idx * section_count;\n");
        sb.append("    const int q_size_y = section_count * 4;\n");
        sb.append("    const int halo_per_chunk = 36 * q_size_y;\n");
        sb.append("    __global const uchar* chunk_biomes = batch_biomes_halo + batch_idx * halo_per_chunk;\n");
        sb.append("    const int min_qx = orig.x >> 2;\n");
        sb.append("    const int min_qy = min_block_y >> 2;\n");
        sb.append("    const int min_qz = orig.z >> 2;\n\n");

        sb.append("    __local int col_world_surface[96];\n");
        sb.append("    __local int chunk_special_flag;\n");
        if (ctx.hasOreVeinRules) {
            sb.append("    __local int ore_has_vein;\n");
            sb.append("    __local float ore_toggle[160];\n");
            sb.append("    __local float ore_vein_a[160];\n");
            sb.append("    __local float ore_vein_b[160];\n");
        }
        final boolean canGpuPack4Bit = ctx.blockPalette.size() <= 64;
        long surfOpaqueMask = 0L;
        long oceanOpaqueMask = 0L;
        if (canGpuPack4Bit) {
            final var surfPred = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
            final var oceanPred = net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
            for (int i = 0; i < ctx.blockPalette.size(); i++) {
                final BlockState st = ctx.blockPalette.get(i);
                if (surfPred.test(st)) {
                    surfOpaqueMask |= (1L << i);
                }
                if (oceanPred.test(st)) {
                    oceanOpaqueMask |= (1L << i);
                }
            }
        }
        sb.append("    if (tid == 0) {\n");
        sb.append("        chunk_special_flag = 0;\n");
        if (ctx.hasOreVeinRules) {
            sb.append("        ore_has_vein = 0;\n");
        }
        sb.append("    }\n");
        sb.append("    const int max_block_y = min_block_y + section_count * 16 - 1;\n");
        sb.append("    int world_surf_y = min_block_y;\n");
        sb.append("    for (int y = max_block_y; y >= min_block_y; y--) {\n");
        sb.append("        const int s_idx = (y - min_block_y) >> 4;\n");
        sb.append("        if (out_voxels[(chunk_base_sec + s_idx) * 4096 + (((y & 15) << 8) | col_idx)] != 0) {\n");
        sb.append("            world_surf_y = y + 1;\n");
        sb.append("            break;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    col_world_surface[((local_lz + 1) << 4) | lx] = world_surf_y;\n");
        sb.append("    if (tid < 32) {\n");
        sb.append("        const int hx = tid & 15;\n");
        sb.append("        const int is_hi = tid >> 4;\n");
        sb.append("        const int hz = (is_hi == 0) ? max((strip << 2) - 1, 0) : min((strip << 2) + 4, 15);\n");
        sb.append("        int h_surf = min_block_y;\n");
        sb.append("        for (int y = max_block_y; y >= min_block_y; y--) {\n");
        sb.append("            const int s_idx = (y - min_block_y) >> 4;\n");
        sb.append("            if (out_voxels[(chunk_base_sec + s_idx) * 4096 + (((y & 15) << 8) | (hz << 4) | hx)] != 0) {\n");
        sb.append("                h_surf = y + 1;\n");
        sb.append("                break;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        col_world_surface[(is_hi == 0 ? 0 : 80) + hx] = h_surf;\n");
        sb.append("    }\n");
        if (ctx.hasOreVeinRules) {
            sb.append("    for (int oidx = tid; oidx < 160; oidx += 64) {\n");
            sb.append("        const int ocx = oidx % 5;\n");
            sb.append("        const int local_cz = (oidx / 5) & 1;\n");
            sb.append("        const int ocz = strip + local_cz;\n");
            sb.append("        const int ocy = oidx / 10;\n");
            sb.append("        const int bx = orig.x + (ocx << 2);\n");
            sb.append("        const int by = -64 + (ocy << 3);\n");
            sb.append("        const int bz = orig.z + (ocz << 2);\n");
            sb.append("        const float ov = ").append(auxCalls.get(ctx.oreVeininessAuxIdx)).append(";\n");
            sb.append("        ore_toggle[oidx] = ov;\n");
            sb.append("        if ((by >= 0 && by <= 56 && ov >= 0.38f) || (by >= -64 && by <= 0 && ov <= -0.38f)) {\n");
            sb.append("            atomic_or(&ore_has_vein, 1);\n");
            sb.append("        }\n");
            sb.append("    }\n");
        }
        sb.append("    const int surf_biome = gl_biome_manager_get(bx, world_surf_y, bz, biome_zoom_seed, chunk_biomes, min_qx, min_qy, min_qz, q_size_y);\n");
        if (ctx.badlandsSurfaceAuxIdx >= 0) {
            sb.append("    if (surf_biome == GL_BIOME_ERODED_BADLANDS) {\n");
            sb.append("        const int by = 0;\n");
            sb.append("        const float bs = fabs(").append(auxCalls.get(ctx.badlandsSurfaceAuxIdx)).append(" * 8.25f);\n");
            sb.append("        const float bp = ").append(auxCalls.get(ctx.badlandsPillarAuxIdx)).append(" * 15.0f;\n");
            sb.append("        const float pbuf = fmin(bs, bp);\n");
            sb.append("        if (pbuf > 0.0f) {\n");
            sb.append("            const float pfloor = fabs(").append(auxCalls.get(ctx.badlandsPillarRoofAuxIdx)).append(" * 1.5f);\n");
            sb.append("            const float ext_top = 64.0f + fmin(pbuf * pbuf * 2.5f, ceil(pfloor * 50.0f) + 24.0f);\n");
            sb.append("            const int ext_start_y = min((int)floor(ext_top), max_block_y);\n");
            sb.append("            if (world_surf_y <= ext_start_y) {\n");
            sb.append("                int hit_water = 0;\n");
            sb.append("                for (int ey = ext_start_y; ey >= min_block_y; ey--) {\n");
            sb.append("                    const uchar ec = out_voxels[(chunk_base_sec + ((ey - min_block_y) >> 4)) * 4096 + (((ey & 15) << 8) | (lz << 4) | lx)];\n");
            sb.append("                    if (ec == 1) break;\n");
            sb.append("                    if (ec == 2) { hit_water = 1; break; }\n");
            sb.append("                }\n");
            sb.append("                if (hit_water == 0) {\n");
            sb.append("                    for (int ey = ext_start_y; ey >= min_block_y; ey--) {\n");
            sb.append("                        const int ev_idx = (chunk_base_sec + ((ey - min_block_y) >> 4)) * 4096 + (((ey & 15) << 8) | (lz << 4) | lx);\n");
            sb.append("                        if (out_voxels[ev_idx] != 0) break;\n");
            sb.append("                        out_voxels[ev_idx] = 1;\n");
            sb.append("                    }\n");
            sb.append("                    if (ext_start_y + 1 > world_surf_y) {\n");
            sb.append("                        world_surf_y = ext_start_y + 1;\n");
            sb.append("                        col_world_surface[((local_lz + 1) << 4) | lx] = world_surf_y;\n");
            sb.append("                    }\n");
            sb.append("                }\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("    }\n");
            if (ctx.icebergSurfaceAuxIdx < 0) {
                sb.append("    if (surf_biome == GL_BIOME_FROZEN_OCEAN || surf_biome == GL_BIOME_DEEP_FROZEN_OCEAN) {\n");
                sb.append("        atomic_or(&chunk_special_flag, 1);\n");
                sb.append("    }\n");
            }
        } else {
            sb.append("    if (surf_biome == GL_BIOME_ERODED_BADLANDS || surf_biome == GL_BIOME_FROZEN_OCEAN || surf_biome == GL_BIOME_DEEP_FROZEN_OCEAN) {\n");
            sb.append("        atomic_or(&chunk_special_flag, 1);\n");
            sb.append("    }\n");
        }
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n");
        if (ctx.hasOreVeinRules) {
            sb.append("    if (chunk_special_flag == 0 && ore_has_vein != 0) {\n");
            sb.append("        for (int oidx = tid; oidx < 160; oidx += 64) {\n");
            sb.append("            const int ocx = oidx % 5;\n");
            sb.append("            const int local_cz = (oidx / 5) & 1;\n");
            sb.append("            const int ocz = strip + local_cz;\n");
            sb.append("            const int ocy = oidx / 10;\n");
            sb.append("            const int bx = orig.x + (ocx << 2);\n");
            sb.append("            const int by = -64 + (ocy << 3);\n");
            sb.append("            const int bz = orig.z + (ocz << 2);\n");
            sb.append("            ore_vein_a[oidx] = ").append(auxCalls.get(ctx.oreVeinAAuxIdx)).append(";\n");
            sb.append("            ore_vein_b[oidx] = ").append(auxCalls.get(ctx.oreVeinBAuxIdx)).append(";\n");
            sb.append("        }\n");
            sb.append("        barrier(CLK_LOCAL_MEM_FENCE);\n");
            sb.append("    }\n");
        }
        sb.append("\n");

        // Exact Vanilla MaterialSystem#buildSurface column walk
        sb.append("    if (chunk_special_flag == 0) {\n");
        sb.append("        const int grad_x = col_world_surface[((local_lz + 1) << 4) | min(lx + 1, 15)] - col_world_surface[((local_lz + 1) << 4) | max(lx - 1, 0)];\n");
        sb.append("        const int grad_z = col_world_surface[((local_lz + 2) << 4) | lx] - col_world_surface[(local_lz << 4) | lx];\n");
        sb.append("        const int by = 0;\n");
        sb.append("        const float surf_noise_val = ").append(auxCalls.get(ctx.surfaceNoiseAuxIdx)).append(";\n");
        sb.append("        ulong ns0 = gl_mth_get_seed(bx, 0, bz) ^ ").append(toUnsignedHex(noiseSeedLo)).append(";\n");
        sb.append("        ulong ns1 = ").append(toUnsignedHex(noiseSeedHi)).append(";\n");
        sb.append("        const int surface_depth = (int)(surf_noise_val * 2.75f + 3.0f + gl_xoroshiro_next_double_f32(&ns0, &ns1) * 0.25f);\n");
        if (ctx.usesSurfaceSecondary) {
            sb.append("        const float surface_secondary = ").append(auxCalls.get(ctx.surfaceSecondaryAuxIdx)).append(";\n");
        } else {
            sb.append("        const float surface_secondary = 0.0f;\n");
        }
        if (ctx.usesClayBands) {
            sb.append("        const float clay_offset = ").append(auxCalls.get(ctx.clayBandsOffsetAuxIdx)).append(";\n");
        } else {
            sb.append("        const float clay_offset = 0.0f;\n");
        }
        if (ctx.usesPrelimSurface) {
            sb.append("        const float4 pc = batch_prelim_corners[batch_idx];\n");
            sb.append("        const float pfx = (float)lx * 0.0625f;\n");
            sb.append("        const float pfz = (float)lz * 0.0625f;\n");
            sb.append("        const float px0 = pc.x + pfx * (pc.y - pc.x);\n");
            sb.append("        const float px1 = pc.z + pfx * (pc.w - pc.z);\n");
            sb.append("        const float prelim_val = px0 + pfz * (px1 - px0);\n");
            sb.append("        const int min_surface_level = (int)floor(prelim_val) + surface_depth - 8;\n");
        } else {
            sb.append("        const int min_surface_level = min_block_y;\n");
        }
        if (!ctx.noise2dToAuxIdx.isEmpty()) {
            sb.append("        float n2d_cache[").append(ctx.noise2dToAuxIdx.size()).append("];\n");
            int slot = 0;
            for (final int auxIdx : ctx.noise2dToAuxIdx.values()) {
                sb.append("        n2d_cache[").append(slot++).append("] = ").append(auxCalls.get(auxIdx)).append(";\n");
            }
        }
        sb.append("        int stone_above = 0;\n");
        sb.append("        int water_height = -2147483648;\n");
        sb.append("        int next_ceiling_y = 2147483647;\n");
        sb.append("        const int start_y = min(world_surf_y, max_block_y);\n");
        sb.append("        for (int y = start_y; y >= min_block_y; y--) {\n");
        sb.append("            const int s_idx = (y - min_block_y) >> 4;\n");
        sb.append("            const int ly = y & 15;\n");
        sb.append("            const int v_idx = (chunk_base_sec + s_idx) * 4096 + ((ly << 8) | (lz << 4) | lx);\n");
        sb.append("            const uchar old_code = out_voxels[v_idx];\n");
        sb.append("            if (old_code == 0) {\n");
        sb.append("                stone_above = 0;\n");
        sb.append("                water_height = -2147483648;\n");
        sb.append("            } else if (old_code == 2 || old_code == 3) {\n");
        sb.append("                if (water_height == -2147483648) {\n");
        sb.append("                    water_height = y + 1;\n");
        sb.append("                }\n");
        sb.append("            } else {\n");
        sb.append("                if (next_ceiling_y >= y) {\n");
        sb.append("                    next_ceiling_y = -2147483648 + 1024;\n");
        sb.append("                    for (int look_y = y - 1; look_y >= min_block_y; look_y--) {\n");
        sb.append("                        const int ls = (look_y - min_block_y) >> 4;\n");
        sb.append("                        const uchar nc = out_voxels[(chunk_base_sec + ls) * 4096 + (((look_y & 15) << 8) | (lz << 4) | lx)];\n");
        sb.append("                        if (nc == 0 || nc == 2 || nc == 3) {\n");
        sb.append("                            next_ceiling_y = look_y + 1;\n");
        sb.append("                            break;\n");
        sb.append("                        }\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("                stone_above++;\n");
        sb.append("                const int stone_below = y - next_ceiling_y + 1;\n");
        sb.append("                const int mat = gl_eval_material_rule(\n");
        sb.append("                    bx, y, bz, biome_zoom_seed, chunk_biomes, min_qx, min_qy, min_qz, q_size_y,\n");
        sb.append("                    surface_depth, surface_secondary, min_surface_level,\n");
        sb.append("                    grad_x, grad_z, stone_above, stone_below, water_height, clay_offset,\n");
        if (ctx.hasOreVeinRules) {
            sb.append("                    lx, lz, ore_has_vein, ore_toggle, ore_vein_a, ore_vein_b,\n");
        }
        if (!ctx.noise2dToAuxIdx.isEmpty()) {
            sb.append("                    n2d_cache,\n");
        }
        sb.append("                    gl_lut_spill);\n");
        sb.append("                if (mat >= 0) {\n");
        sb.append("                    out_voxels[v_idx] = (uchar)mat;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        if (ctx.icebergSurfaceAuxIdx >= 0) {
            sb.append("        if (surf_biome == GL_BIOME_FROZEN_OCEAN || surf_biome == GL_BIOME_DEEP_FROZEN_OCEAN) {\n");
            sb.append("            const float ice_surf = fabs(").append(auxCalls.get(ctx.icebergSurfaceAuxIdx)).append(" * 8.25f);\n");
            sb.append("            const float ice_pil = ").append(auxCalls.get(ctx.icebergPillarAuxIdx)).append(" * 15.0f;\n");
            sb.append("            const float iceberg = fmin(ice_surf, ice_pil);\n");
            sb.append("            if (iceberg > 1.8f) {\n");
            sb.append("                const float ice_roof = fabs(").append(auxCalls.get(ctx.icebergPillarRoofAuxIdx)).append(" * 1.5f);\n");
            sb.append("                float top = fmin(iceberg * iceberg * 1.2f, ceil(ice_roof * 40.0f) + 14.0f);\n");
            sb.append("                if (surf_biome == GL_BIOME_DEEP_FROZEN_OCEAN || gl_frozen_ocean_should_melt(bx, bz)) {\n");
            sb.append("                    top -= 2.0f;\n");
            sb.append("                }\n");
            sb.append("                if (top > 2.0f) {\n");
            sb.append("                    const int ext_bot = (int)((float)").append(ctx.seaLevel).append(" - top - 7.0f);\n");
            sb.append("                    const int ext_top = (int)(top + (float)").append(ctx.seaLevel).append(");\n");
            sb.append("                    ulong fs0 = gl_mth_get_seed(bx, 0, bz) ^ ").append(toUnsignedHex(noiseSeedLo)).append(";\n");
            sb.append("                    ulong fs1 = ").append(toUnsignedHex(noiseSeedHi)).append(";\n");
            sb.append("                    const int max_snow = 2 + (int)((((ulong)(uint)(gl_xoroshiro_next(&fs0, &fs1) >> 32)) * 4UL) >> 32);\n");
            sb.append("                    const int min_snow_y = ").append(ctx.seaLevel + 18).append(" + (int)((((ulong)(uint)(gl_xoroshiro_next(&fs0, &fs1) >> 32)) * 10UL) >> 32);\n");
            sb.append("                    int snow_d = 0;\n");
            sb.append("                    const int fy_start = min(max(world_surf_y, ext_top + 1), max_block_y);\n");
            sb.append("                    const int fy_end = max(min_surface_level, min_block_y);\n");
            sb.append("                    for (int fy = fy_start; fy >= fy_end; fy--) {\n");
            sb.append("                        const int fv_idx = (chunk_base_sec + ((fy - min_block_y) >> 4)) * 4096 + (((fy & 15) << 8) | (lz << 4) | lx);\n");
            sb.append("                        const uchar fc = out_voxels[fv_idx];\n");
            sb.append("                        int place_ice = 0;\n");
            sb.append("                        if (fc == 0 && fy < ext_top && gl_xoroshiro_next_double_f32(&fs0, &fs1) > 0.01f) {\n");
            sb.append("                            place_ice = 1;\n");
            sb.append("                        } else if (fc == 2 && fy > ext_bot && fy < ").append(ctx.seaLevel).append(" && gl_xoroshiro_next_double_f32(&fs0, &fs1) > 0.15f) {\n");
            sb.append("                            place_ice = 1;\n");
            sb.append("                        }\n");
            sb.append("                        if (place_ice != 0) {\n");
            sb.append("                            if (snow_d <= max_snow && fy > min_snow_y) {\n");
            sb.append("                                out_voxels[fv_idx] = (uchar)").append(ctx.snowBlockId).append(";\n");
            sb.append("                                snow_d++;\n");
            sb.append("                            } else {\n");
            sb.append("                                out_voxels[fv_idx] = (uchar)").append(ctx.packedIceId).append(";\n");
            sb.append("                            }\n");
            sb.append("                        }\n");
            sb.append("                    }\n");
            sb.append("                }\n");
            sb.append("            }\n");
            sb.append("        }\n");
        }
        sb.append("    }\n\n");

        // Fast top-down heightmap scan when Pass 2 is the final voxel-mutating pass (packing runs in Pass 5 across batchCount * sectionCount WGs)
        if (canGpuPack4Bit) {
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
        }
        sb.append("    if (tid == 0 && chunk_special_flag != 0) {\n");
        sb.append("        out_chunk_flags[batch_idx] = (uchar)1;\n");
        sb.append("    }\n");
        sb.append("}\n");
    }

    private static OpenClBackendEmitter.DensityVolumeProgram buildPackSectionsProgram(final List<BlockState> blockPalette) {
        final boolean canGpuPack4Bit = blockPalette.size() <= 64;
        long tickingFluidMask = 0L;
        if (canGpuPack4Bit) {
            for (int i = 0; i < blockPalette.size(); i++) {
                final var fs = blockPalette.get(i).getFluidState();
                if (!fs.isEmpty() && fs.isRandomlyTicking()) {
                    tickingFluidMask |= (1L << i);
                }
            }
        }
        final StringBuilder sb = new StringBuilder(6144);
        sb.append("#pragma OPENCL FP_CONTRACT OFF\n\n");
        if (canGpuPack4Bit) {
            // Exact 4x4 2D Hilbert LUT matching LcaSectionCodec#xyToHilbert2D4 for octreeMask64.
            sb.append("__constant uchar GOLDLEAF_H2_4X4[16] = {0,1,14,15, 3,2,13,12, 4,7,8,11, 5,6,9,10};\n\n");
        }
        sb.append("__kernel void ").append(PACK_KERNEL_NAME).append("(\n");
        sb.append("    __global uchar* restrict out_voxels,\n");
        sb.append("    __global ushort* restrict out_meta,\n");
        sb.append("    const int batch_count,\n");
        sb.append("    const int section_count,\n");
        sb.append("    __global const uint* restrict block_dict_ids,\n");
        sb.append("    const int has_block_dict)\n");
        sb.append("{\n");
        sb.append("    const int sec_gid = (int)get_group_id(0);\n");
        sb.append("    const int tid = (int)get_local_id(0);\n");
        sb.append("    const int total_sections = batch_count * section_count;\n");
        sb.append("    if (sec_gid >= total_sections) return;\n");
        sb.append("    const int lx = tid & 15;\n");
        sb.append("    const int lz = tid >> 4;\n");
        sb.append("    const int sec_base = sec_gid * 4096;\n");
        sb.append("    __local uint sec_non_empty[256];\n");
        sb.append("    __local uint sec_fluid_cnt[256];\n");
        sb.append("    __local uint sec_min_code[256];\n");
        sb.append("    __local uint sec_max_code[256];\n");
        if (canGpuPack4Bit) {
            sb.append("    __local ulong sec_used_mask[256];\n");
            sb.append("    __local uint sec_tick_fluid[256];\n");
            sb.append("    __local ulong sec_octree_mask[256];\n");
        }
        sb.append("    uint l_non_empty = 0u;\n");
        sb.append("    uint l_fluid = 0u;\n");
        sb.append("    uint l_min = 255u;\n");
        sb.append("    uint l_max = 0u;\n");
        if (canGpuPack4Bit) {
            sb.append("    ulong l_mask = 0UL;\n");
            sb.append("    uint l_tick_fluid = 0u;\n");
        }
        sb.append("    for (int ly = 0; ly < 16; ly++) {\n");
        sb.append("        const uint c = (uint)out_voxels[sec_base + ((ly << 8) | (lz << 4) | lx)];\n");
        sb.append("        if (c != 0u) l_non_empty++;\n");
        sb.append("        if (c == 2u || c == 3u) l_fluid++;\n");
        sb.append("        if (c < l_min) l_min = c;\n");
        sb.append("        if (c > l_max) l_max = c;\n");
        if (canGpuPack4Bit) {
            sb.append("        const ulong c_bit = (1UL << c);\n");
            sb.append("        l_mask |= c_bit;\n");
            if (tickingFluidMask != 0L) {
                sb.append("        if ((").append(toUnsignedHex(tickingFluidMask)).append(" & c_bit) != 0UL) l_tick_fluid++;\n");
            }
        }
        sb.append("    }\n");
        sb.append("    sec_non_empty[tid] = l_non_empty;\n");
        sb.append("    sec_fluid_cnt[tid] = l_fluid;\n");
        sb.append("    sec_min_code[tid] = l_min;\n");
        sb.append("    sec_max_code[tid] = l_max;\n");
        if (canGpuPack4Bit) {
            sb.append("    sec_used_mask[tid] = l_mask;\n");
            sb.append("    sec_tick_fluid[tid] = l_tick_fluid;\n");
        }
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("    for (int stride = 128; stride > 0; stride >>= 1) {\n");
        sb.append("        if (tid < stride) {\n");
        sb.append("            sec_non_empty[tid] += sec_non_empty[tid + stride];\n");
        sb.append("            sec_fluid_cnt[tid] += sec_fluid_cnt[tid + stride];\n");
        sb.append("            sec_min_code[tid] = min(sec_min_code[tid], sec_min_code[tid + stride]);\n");
        sb.append("            sec_max_code[tid] = max(sec_max_code[tid], sec_max_code[tid + stride]);\n");
        if (canGpuPack4Bit) {
            sb.append("            sec_used_mask[tid] |= sec_used_mask[tid + stride];\n");
            sb.append("            sec_tick_fluid[tid] += sec_tick_fluid[tid + stride];\n");
        }
        sb.append("        }\n");
        sb.append("        barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("    }\n");
        if (canGpuPack4Bit) {
            sb.append("    const ulong used_mask = sec_used_mask[0];\n");
            sb.append("    const uint pal_len = (uint)popcount(used_mask);\n");
            sb.append("    const int pack_flat = (sec_non_empty[0] > 0u && sec_min_code[0] != sec_max_code[0] && pal_len <= 16u) ? 1 : 0;\n");
            sb.append("    if (pack_flat != 0) {\n");
            sb.append("        const uint flat_bits = (pal_len <= 2u) ? 1u : (pal_len <= 4u) ? 2u : (pal_len <= 8u) ? 3u : 4u;\n");
            sb.append("        const int row_base = sec_base + (tid << 4);\n");
            sb.append("        const uint row_ly = (uint)(tid >> 4);\n");
            sb.append("        const uint row_lz = (uint)(tid & 15);\n");
            sb.append("        const uint oct_yz = ((row_ly >> 2) << 4);\n");
            sb.append("        const uint oct_z4 = ((row_lz >> 2) << 2);\n");
            sb.append("        ulong word = 0UL;\n");
            sb.append("        ulong l_octree = 0UL;\n");
            sb.append("        for (uint px = 0u; px < 16u; px++) {\n");
            sb.append("            const uint pc = (uint)out_voxels[row_base + (int)px];\n");
            sb.append("            const ulong lid = popcount(used_mask & ((1UL << pc) - 1UL));\n");
            sb.append("            word |= (lid << (px * flat_bits));\n");
            sb.append("            if (lid != 0UL) {\n");
            sb.append("                const uint oct6 = oct_yz | (uint)GOLDLEAF_H2_4X4[oct_z4 | (px >> 2)];\n");
            sb.append("                l_octree |= (1UL << oct6);\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("        sec_octree_mask[tid] = l_octree;\n");
            sb.append("        barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);\n");
            sb.append("        if (flat_bits == 4u) {\n");
            sb.append("            ((__global ulong*)(out_voxels + sec_base))[tid] = word;\n");
            sb.append("        } else if (flat_bits == 2u) {\n");
            sb.append("            ((__global uint*)(out_voxels + sec_base))[tid] = (uint)word;\n");
            sb.append("        } else if (flat_bits == 1u) {\n");
            sb.append("            ((__global ushort*)(out_voxels + sec_base))[tid] = (ushort)word;\n");
            sb.append("        } else {\n");
            sb.append("            __global uchar* dst6 = out_voxels + sec_base + (tid * 6);\n");
            sb.append("            dst6[0] = (uchar)(word & 0xFFUL);\n");
            sb.append("            dst6[1] = (uchar)((word >> 8) & 0xFFUL);\n");
            sb.append("            dst6[2] = (uchar)((word >> 16) & 0xFFUL);\n");
            sb.append("            dst6[3] = (uchar)((word >> 24) & 0xFFUL);\n");
            sb.append("            dst6[4] = (uchar)((word >> 32) & 0xFFUL);\n");
            sb.append("            dst6[5] = (uchar)((word >> 40) & 0xFFUL);\n");
            sb.append("        }\n");
            sb.append("        for (int stride = 128; stride > 0; stride >>= 1) {\n");
            sb.append("            if (tid < stride) {\n");
            sb.append("                sec_octree_mask[tid] |= sec_octree_mask[tid + stride];\n");
            sb.append("            }\n");
            sb.append("            barrier(CLK_LOCAL_MEM_FENCE);\n");
            sb.append("        }\n");
            sb.append("        if (tid == 0) {\n");
            sb.append("            ((__global ulong*)(out_voxels + sec_base + 2048))[0] = used_mask;\n");
            sb.append("            ((__global ushort*)(out_voxels + sec_base + 2056))[0] = (ushort)sec_tick_fluid[0];\n");
            sb.append("            out_voxels[sec_base + 2058] = (uchar)flat_bits;\n");
            sb.append("            out_voxels[sec_base + 2059] = (uchar)pal_len;\n");
            sb.append("            ((__global ulong*)(out_voxels + sec_base + 2064))[0] = sec_octree_mask[0];\n");
            sb.append("            __global uint* dict_out = (__global uint*)(out_voxels + sec_base + 2072);\n");
            sb.append("            ulong rem = used_mask;\n");
            sb.append("            uint k = 0u;\n");
            sb.append("            while (rem != 0UL && k < 16u) {\n");
            sb.append("                const uint code = 63u - (uint)clz(rem & (0UL - rem));\n");
            sb.append("                rem &= (rem - 1UL);\n");
            sb.append("                dict_out[k++] = (has_block_dict != 0) ? block_dict_ids[code] : 0xFFFFFFFFu;\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("    }\n");
            sb.append("    if (tid == 0) {\n");
            sb.append("        const int m_off = sec_gid * 4;\n");
            sb.append("        out_meta[m_off + 0] = (ushort)sec_non_empty[0];\n");
            sb.append("        out_meta[m_off + 1] = (ushort)(sec_min_code[0] == sec_max_code[0] ? 1 : (pack_flat != 0 ? 2 : 0));\n");
            sb.append("        out_meta[m_off + 2] = (ushort)sec_min_code[0];\n");
            sb.append("        out_meta[m_off + 3] = (ushort)sec_fluid_cnt[0];\n");
            sb.append("    }\n");
        } else {
            sb.append("    if (tid == 0) {\n");
            sb.append("        const int m_off = sec_gid * 4;\n");
            sb.append("        out_meta[m_off + 0] = (ushort)sec_non_empty[0];\n");
            sb.append("        out_meta[m_off + 1] = (ushort)(sec_min_code[0] == sec_max_code[0] ? 1 : 0);\n");
            sb.append("        out_meta[m_off + 2] = (ushort)sec_min_code[0];\n");
            sb.append("        out_meta[m_off + 3] = (ushort)sec_fluid_cnt[0];\n");
            sb.append("    }\n");
        }
        sb.append("}\n");
        final String src = sb.toString();
        final int[] emptySpill = new int[0];
        return new OpenClBackendEmitter.DensityVolumeProgram(
            PACK_KERNEL_NAME,
            src,
            sha256("PACK_U8_V2_DICT\n" + src, emptySpill),
            emptySpill,
            0,
            0,
            0
        );
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

    private static @Nullable String buildCombinerCallExpr(
        final String primaryPrefix,
        final OpenClBackendEmitter.CoarseInterpSpec coarseSpec
    ) {
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
            while ((pos = primaryPrefix.indexOf(prefix, pos)) >= 0) {
                final int nameStart = pos + prefix.length();
                final int paren = primaryPrefix.indexOf('(', nameStart);
                if (paren > nameStart) {
                    final String candidate = primaryPrefix.substring(nameStart, paren).trim();
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
        final int sigIdx = primaryPrefix.indexOf(evalFn + "(");
        final int sigEnd = sigIdx >= 0 ? primaryPrefix.indexOf(')', sigIdx) : -1;
        final boolean acceptsSpill = sigIdx >= 0 && sigEnd > sigIdx
            && primaryPrefix.substring(sigIdx, sigEnd).contains("gl_lut_spill");
        final StringBuilder call = new StringBuilder(evalFn).append("(bx, by, bz");
        for (int k = 0; k < coarseSpec.coarseFunctionNames().size(); k++) {
            call.append(", interp_").append(k);
        }
        if (acceptsSpill) {
            call.append(", gl_lut_spill");
        }
        call.append(')');
        return returnsQ32 ? ("q32_to_f32(" + call + ")") : ("(float)(" + call + ")");
    }

    private static String toUnsignedHex(final long v) {
        return "0x" + Long.toHexString(v).toUpperCase() + "UL";
    }

    private static String formatFloatLiteral(final double v) {
        if (v >= 1.0e30) {
            return "1.0e30f";
        }
        if (v <= -1.0e30) {
            return "-1.0e30f";
        }
        final float f = (float) v;
        final String s = Float.toString(f);
        return (s.contains(".") || s.contains("E") || s.contains("e")) ? (s + "f") : (s + ".0f");
    }

    private static String sha256(final String source, final int[] spilled) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(source.getBytes(StandardCharsets.UTF_8));
            final byte[] buf = new byte[4];
            for (final int w : spilled) {
                buf[0] = (byte) (w >>> 24);
                buf[1] = (byte) (w >>> 16);
                buf[2] = (byte) (w >>> 8);
                buf[3] = (byte) w;
                md.update(buf);
            }
            final byte[] hash = md.digest();
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (final byte b : hash) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
