package dev.leafcraft.goldleaf.worldgen;

import ca.spottedleaf.moonrise.spi.PlayerChunkPrioritySpi;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.storage.ColdChunkSavePolicy;
import io.papermc.paper.spi.PaperWorldgenSpi;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 bootstrap registrar for worldgen acceleration, section-batched terrain fill,
 * cold-chunk save policy hooks, underground ore acceleration, and directional chunk priority
 * ({@code goldleaf-ADR-004}, {@code goldleaf-ADR-017}).
 */
public final class WorldgenBootstrapRegistrar {

    private WorldgenBootstrapRegistrar() {
    }

    public static void register(final @Nullable GoldLeafConfig config) {
        PaperWorldgenSpi.register(new PaperWorldgenSpi.Provider() {
            @Override
            public boolean tryAccelerateBiomes(
                final WorldGenContext context,
                final ServerLevel level,
                final ChunkGenerator generator,
                final RandomState randomState,
                final Blender blender,
                final StructureManager structureManager,
                final ChunkAccess chunk
            ) {
                final GoldLeafConfig glConfig = GoldLeafConfig.get();
                final boolean requireAccel = glConfig != null && glConfig.worldgen != null && glConfig.worldgen.requireAccelerator;
                if (!requireAccel && !TerrainGenerationAccelerator.isActive()) {
                    return false;
                }
                final TerrainGenerationAccelerator.BiomeTask biomeTask = new TerrainGenerationAccelerator.BiomeTask(
                    level, generator, randomState, blender, structureManager, chunk
                );
                return TerrainGenerationAccelerator.dispatchSingleBiome(
                    context, biomeTask, requireAccel, WorldgenWaveTelemetry.GLOBAL
                );
            }

            @Override
            public @Nullable CompletableFuture<@Nullable ChunkAccess> tryAccelerateBiomesAsync(
                final WorldGenContext context,
                final ServerLevel level,
                final ChunkGenerator generator,
                final RandomState randomState,
                final Blender blender,
                final StructureManager structureManager,
                final ChunkAccess chunk
            ) {
                final GoldLeafConfig glConfig = GoldLeafConfig.get();
                final boolean requireAccel = glConfig != null && glConfig.worldgen != null && glConfig.worldgen.requireAccelerator;
                if (!requireAccel && !TerrainGenerationAccelerator.isActive()) {
                    return null;
                }
                final TerrainGenerationAccelerator.BiomeTask biomeTask = new TerrainGenerationAccelerator.BiomeTask(
                    level, generator, randomState, blender, structureManager, chunk
                );
                return TerrainGenerationAccelerator.dispatchSingleBiomeAsync(
                    context, biomeTask, requireAccel, WorldgenWaveTelemetry.GLOBAL
                );
            }

            @Override
            public boolean tryAccelerateTerrain(
                final ServerLevel level,
                final NoiseBasedChunkGenerator generator,
                final DensityVolume volume,
                final NoiseGeneratorSettings settings,
                final RandomState randomState,
                final StructureManager structureManager,
                final Blender blender,
                final ChunkAccess chunk
            ) {
                final GoldLeafConfig glConfig = GoldLeafConfig.get();
                final boolean requireAccel = glConfig != null && glConfig.worldgen != null && glConfig.worldgen.requireAccelerator;
                if (!requireAccel && !TerrainGenerationAccelerator.isActive()) {
                    return false;
                }
                final TerrainGenerationAccelerator.TerrainTask terrainTask = new TerrainGenerationAccelerator.TerrainTask(
                    level,
                    generator,
                    volume,
                    settings.noiseRouter(),
                    settings,
                    randomState,
                    Beardifier.forStructuresInChunk(structureManager, chunk.getPos()),
                    blender,
                    chunk,
                    structureManager.level
                );
                return TerrainGenerationAccelerator.dispatchSingleTerrain(
                    null, terrainTask, requireAccel, WorldgenWaveTelemetry.GLOBAL
                );
            }

            @Override
            public @Nullable CompletableFuture<@Nullable ChunkAccess> tryAccelerateTerrainAsync(
                final ServerLevel level,
                final NoiseBasedChunkGenerator generator,
                final DensityVolume volume,
                final NoiseGeneratorSettings settings,
                final RandomState randomState,
                final StructureManager structureManager,
                final Blender blender,
                final ChunkAccess chunk
            ) {
                final GoldLeafConfig glConfig = GoldLeafConfig.get();
                final boolean requireAccel = glConfig != null && glConfig.worldgen != null && glConfig.worldgen.requireAccelerator;
                if (!requireAccel && !TerrainGenerationAccelerator.isActive()) {
                    return null;
                }
                final TerrainGenerationAccelerator.TerrainTask terrainTask = new TerrainGenerationAccelerator.TerrainTask(
                    level,
                    generator,
                    volume,
                    settings.noiseRouter(),
                    settings,
                    randomState,
                    Beardifier.forStructuresInChunk(structureManager, chunk.getPos()),
                    blender,
                    chunk,
                    structureManager.level
                );
                return TerrainGenerationAccelerator.dispatchSingleTerrainAsync(
                    null, terrainTask, requireAccel, WorldgenWaveTelemetry.GLOBAL
                );
            }

            @Override
            public boolean tryFillChunkBatched(
                final NoiseChunk noiseChunk,
                final ChunkAccess chunk,
                final ScopedDensityBuffer densityBuffer,
                final BlockState defaultBlock,
                final BlockState airState,
                final PaperWorldgenSpi.DebugStateTransform debugTransform
            ) {
                final GoldLeafConfig glConfig = GoldLeafConfig.get();
                if (glConfig != null && glConfig.worldgen != null && !glConfig.worldgen.sectionBatchedDoFill) {
                    return false;
                }
                SectionBatchedTerrainFill.fillChunk(
                    noiseChunk,
                    chunk,
                    densityBuffer,
                    defaultBlock,
                    airState,
                    debugTransform::apply
                );
                return true;
            }

            @Override
            public boolean isSurfaceAlreadyApplied(final ChunkAccess chunk) {
                return TerrainGenerationAccelerator.get().isSurfaceAlreadyApplied(chunk);
            }

            @Override
            public boolean isCarverAlreadyApplied(final ChunkAccess chunk) {
                return TerrainGenerationAccelerator.get().isCarverAlreadyApplied(chunk);
            }

            @Override
            public PaperWorldgenSpi.@Nullable PrecomputedChunkLight tryComputeChunkLight(
                final ChunkAccess chunk,
                final Boolean[] emptySections,
                final boolean hasSkyLight
            ) {
                return TerrainGenerationAccelerator.get().tryComputeChunkLight(chunk, emptySections, hasSkyLight);
            }

            @Override
            public @Nullable CompletableFuture<PaperWorldgenSpi.@Nullable PrecomputedChunkLight> tryComputeChunkLightAsync(
                final ChunkAccess chunk,
                final Boolean[] emptySections,
                final boolean hasSkyLight
            ) {
                return TerrainGenerationAccelerator.get().tryComputeChunkLightAsync(chunk, emptySections, hasSkyLight);
            }

            @Override
            public void recordStageTiming(final int stageOrdinal, final long elapsedNanos) {
                WorldgenStageProfiler.GLOBAL.record(stageOrdinal, elapsedNanos);
            }

            @Override
            public void recordFreshChunkGenerated(final ServerLevel level, final ChunkAccess chunk) {
                final ColdChunkSavePolicy policy = ColdChunkSavePolicy.global();
                if (!policy.isEnabled() || level == null || chunk == null) {
                    return;
                }
                policy.recordFreshGeneration(level.dimension().identifier().toString(), chunk.coordinateKey);
            }

            @Override
            public boolean shouldSkipColdChunkSave(final ChunkAccess chunk) {
                final ColdChunkSavePolicy policy = ColdChunkSavePolicy.global();
                if (!policy.isEnabled() || chunk == null) {
                    return false;
                }
                final boolean isLevelChunk = chunk instanceof LevelChunk;
                String worldId = "default";
                if (isLevelChunk) {
                    final Level lvl = ((LevelChunk) chunk).getLevel();
                    if (lvl instanceof ServerLevel sl) {
                        worldId = sl.dimension().identifier().toString();
                    }
                } else if (chunk.getHeightAccessorForGeneration() instanceof ServerLevel sl) {
                    worldId = sl.dimension().identifier().toString();
                }
                return policy.shouldSkipSave(
                    worldId,
                    isLevelChunk,
                    chunk.goldleaf$isGeneratedFresh(),
                    chunk.goldleaf$isPostGenModified(),
                    chunk.persistentDataContainer.dirty(),
                    chunk.getInhabitedTime(),
                    chunk.coordinateKey
                );
            }

            @Override
            public boolean shouldIgnoreNaturalTickModification() {
                return ColdChunkSavePolicy.global().shouldIgnoreNaturalTickModification();
            }

            @Override
            public void enterNaturalTick() {
                ColdChunkSavePolicy.enterNaturalTick();
            }

            @Override
            public void exitNaturalTick() {
                ColdChunkSavePolicy.exitNaturalTick();
            }

            @Override
            public @Nullable Executor terrainContinuationExecutor() {
                final TerrainGenerationAccelerator accel = TerrainGenerationAccelerator.get();
                if (accel instanceof IrOpenClTerrainAccelerator openCl) {
                    return openCl.terrainContinuationExecutor();
                }
                return null;
            }

            @Override
            public boolean tryAccelerateUndergroundOres(
                final WorldGenLevel level,
                final ChunkGenerator generator,
                final ChunkAccess centerChunk,
                final WorldgenRandom random,
                final long decorationSeed,
                final Reference2LongMap<Holder<Feature>> configuredFeatureSeeds,
                final boolean hasConfiguredFeatureSeeds,
                final BlockPos origin,
                final int stepIndex,
                final List<PlacedFeature> stepFeatures,
                final BitSet activeFeatureMask,
                final FeaturePlacer placer
            ) {
                final TerrainGenerationAccelerator accel = TerrainGenerationAccelerator.get();
                if (accel instanceof IrOpenClTerrainAccelerator openCl) {
                    return openCl.orePlacementEngine().accelerateUndergroundOres(
                        level,
                        generator,
                        centerChunk,
                        random,
                        decorationSeed,
                        configuredFeatureSeeds,
                        hasConfiguredFeatureSeeds,
                        origin,
                        stepIndex,
                        stepFeatures,
                        activeFeatureMask,
                        placer
                    );
                }
                final GoldLeafConfig cfg = GoldLeafConfig.get();
                if (cfg != null && cfg.worldgen != null && cfg.worldgen.hilbertOreVeins) {
                    return GpuOrePlacementEngine.accelerateUndergroundOresHilbertStatic(
                        level,
                        generator,
                        centerChunk,
                        random,
                        decorationSeed,
                        configuredFeatureSeeds,
                        hasConfiguredFeatureSeeds,
                        origin,
                        stepIndex,
                        stepFeatures,
                        activeFeatureMask,
                        placer
                    );
                }
                return false;
            }
        });

        if (config != null && config.worldgen != null && config.worldgen.directionalChunkPriority != null) {
            final GoldLeafConfig.DirectionalChunkPriority dcp = config.worldgen.directionalChunkPriority;
            PlayerChunkPrioritySpi.register(
                PlayerChunkPrioritySpi.Settings.of(
                    dcp.enabled,
                    dcp.lookBias,
                    dcp.velocityBias,
                    dcp.innerRadialRadius,
                    dcp.yawReprioritizeDegrees
                )
            );
        } else {
            PlayerChunkPrioritySpi.register(PlayerChunkPrioritySpi.Settings.DEFAULT);
        }
    }
}
