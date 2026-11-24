package io.papermc.paper.spi;

import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate worldgen acceleration SPI for {@code ChunkStatusTasks} and
 * {@code NoiseBasedChunkGenerator}.
 *
 * <p>Zero product or config coupling: returns {@code false} when no Layer 2 accelerator is
 * registered so vanilla chunk generation executes untouched.</p>
 */
public final class PaperWorldgenSpi {

    public static final int STAGE_STRUCTURE_STARTS = 0;
    public static final int STAGE_STRUCTURE_REFERENCES = 1;
    public static final int STAGE_BIOMES = 2;
    public static final int STAGE_TERRAIN_TOTAL = 3;
    public static final int STAGE_TERRAIN_DO_FILL = 4;
    public static final int STAGE_TERRAIN_BUILD_SURFACE = 5;
    public static final int STAGE_TERRAIN_CARVERS = 6;
    public static final int STAGE_TERRAIN_HEIGHTMAPS = 7;
    public static final int STAGE_FEATURES = 8;
    public static final int STAGE_INITIALIZE_LIGHT = 9;
    public static final int STAGE_LIGHT = 10;
    public static final int STAGE_SPAWN = 11;
    public static final int STAGE_FULL = 12;
    public static final int STAGE_CARVER_MASK = 13;
    public static final int STAGE_CARVER_APPLY = 14;
    public static final int STAGE_FEAT_STEP_BASE = 15;
    public static final int STAGE_FEAT_FIX_SNOW = 26;

    @FunctionalInterface
    public interface DebugStateTransform {
        BlockState apply(NoiseChunk noiseChunk, int posX, int posY, int posZ, BlockState state);
    }

    public static final byte LIGHT_FLAG_SKY_ALL_ZERO = 1 << 0;
    public static final byte LIGHT_FLAG_SKY_ALL_15 = 1 << 1;
    public static final byte LIGHT_FLAG_BLK_ALL_ZERO = 1 << 2;
    public static final byte LIGHT_FLAG_BOTTOM_LEAK = 1 << 3;

    public record PrecomputedChunkLight(
        int activeSections,
        boolean hasBlockEmission,
        byte[] secFlags,
        int secFlagsOffset,
        byte[] skyNibbles,
        int skyNibblesOffset,
        byte @Nullable [] blkNibbles,
        int blkNibblesOffset
    ) {
        public boolean hasBottomLeak() {
            return activeSections > 0 && (secFlags[secFlagsOffset] & LIGHT_FLAG_BOTTOM_LEAK) != 0;
        }
    }

    public interface Provider {
        default boolean tryAccelerateBiomes(
            final WorldGenContext context,
            final ServerLevel level,
            final ChunkGenerator generator,
            final RandomState randomState,
            final Blender blender,
            final StructureManager structureManager,
            final ChunkAccess chunk
        ) {
            return false;
        }

        default @Nullable CompletableFuture<@Nullable ChunkAccess> tryAccelerateBiomesAsync(
            final WorldGenContext context,
            final ServerLevel level,
            final ChunkGenerator generator,
            final RandomState randomState,
            final Blender blender,
            final StructureManager structureManager,
            final ChunkAccess chunk
        ) {
            return null;
        }

        default boolean tryAccelerateTerrain(
            final ServerLevel level,
            final NoiseBasedChunkGenerator generator,
            final DensityVolume volume,
            final NoiseGeneratorSettings settings,
            final RandomState randomState,
            final StructureManager structureManager,
            final Blender blender,
            final ChunkAccess chunk
        ) {
            return false;
        }

        default @Nullable CompletableFuture<@Nullable ChunkAccess> tryAccelerateTerrainAsync(
            final ServerLevel level,
            final NoiseBasedChunkGenerator generator,
            final DensityVolume volume,
            final NoiseGeneratorSettings settings,
            final RandomState randomState,
            final StructureManager structureManager,
            final Blender blender,
            final ChunkAccess chunk
        ) {
            return null;
        }

        default boolean tryFillChunkBatched(
            final NoiseChunk noiseChunk,
            final ChunkAccess chunk,
            final ScopedDensityBuffer densityBuffer,
            final BlockState defaultBlock,
            final BlockState airState,
            final DebugStateTransform debugTransform
        ) {
            return false;
        }

        default boolean isSurfaceAlreadyApplied(final ChunkAccess chunk) {
            return false;
        }

        default boolean isCarverAlreadyApplied(final ChunkAccess chunk) {
            return false;
        }

        default void recordStageTiming(final int stageOrdinal, final long elapsedNanos) {
        }

        default @Nullable Executor terrainContinuationExecutor() {
            return null;
        }

        default boolean tryAccelerateUndergroundOres(
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
            return false;
        }

        default @Nullable PrecomputedChunkLight tryComputeChunkLight(
            final ChunkAccess chunk,
            final Boolean[] emptySections,
            final boolean hasSkyLight
        ) {
            return null;
        }

        default @Nullable CompletableFuture<@Nullable PrecomputedChunkLight> tryComputeChunkLightAsync(
            final ChunkAccess chunk,
            final Boolean[] emptySections,
            final boolean hasSkyLight
        ) {
            return null;
        }

        default void recordFreshChunkGenerated(final ServerLevel level, final ChunkAccess chunk) {
        }

        default boolean shouldSkipColdChunkSave(final ChunkAccess chunk) {
            return false;
        }

        default boolean shouldIgnoreNaturalTickModification() {
            return false;
        }

        default void enterNaturalTick() {
        }

        default void exitNaturalTick() {
        }
    }

    private static volatile @Nullable Provider provider;

    private PaperWorldgenSpi() {
    }

    public static void register(final @Nullable Provider p) {
        provider = p;
    }

    public static @Nullable Provider get() {
        return provider;
    }

    public static void resetDefault() {
        provider = null;
    }

    public static boolean tryAccelerateBiomes(
        final WorldGenContext context,
        final ServerLevel level,
        final ChunkGenerator generator,
        final RandomState randomState,
        final Blender blender,
        final StructureManager structureManager,
        final ChunkAccess chunk
    ) {
        final Provider p = provider;
        return p != null && p.tryAccelerateBiomes(context, level, generator, randomState, blender, structureManager, chunk);
    }

    public static @Nullable CompletableFuture<@Nullable ChunkAccess> tryAccelerateBiomesAsync(
        final WorldGenContext context,
        final ServerLevel level,
        final ChunkGenerator generator,
        final RandomState randomState,
        final Blender blender,
        final StructureManager structureManager,
        final ChunkAccess chunk
    ) {
        final Provider p = provider;
        return p != null ? p.tryAccelerateBiomesAsync(context, level, generator, randomState, blender, structureManager, chunk) : null;
    }

    public static boolean tryAccelerateTerrain(
        final ServerLevel level,
        final NoiseBasedChunkGenerator generator,
        final DensityVolume volume,
        final NoiseGeneratorSettings settings,
        final RandomState randomState,
        final StructureManager structureManager,
        final Blender blender,
        final ChunkAccess chunk
    ) {
        final Provider p = provider;
        return p != null && p.tryAccelerateTerrain(level, generator, volume, settings, randomState, structureManager, blender, chunk);
    }

    public static @Nullable CompletableFuture<@Nullable ChunkAccess> tryAccelerateTerrainAsync(
        final ServerLevel level,
        final NoiseBasedChunkGenerator generator,
        final DensityVolume volume,
        final NoiseGeneratorSettings settings,
        final RandomState randomState,
        final StructureManager structureManager,
        final Blender blender,
        final ChunkAccess chunk
    ) {
        final Provider p = provider;
        return p != null ? p.tryAccelerateTerrainAsync(level, generator, volume, settings, randomState, structureManager, blender, chunk) : null;
    }

    public static boolean tryFillChunkBatched(
        final NoiseChunk noiseChunk,
        final ChunkAccess chunk,
        final ScopedDensityBuffer densityBuffer,
        final BlockState defaultBlock,
        final BlockState airState,
        final DebugStateTransform debugTransform
    ) {
        final Provider p = provider;
        return p != null && p.tryFillChunkBatched(noiseChunk, chunk, densityBuffer, defaultBlock, airState, debugTransform);
    }

    public static boolean isSurfaceAlreadyApplied(final ChunkAccess chunk) {
        final Provider p = provider;
        return p != null && p.isSurfaceAlreadyApplied(chunk);
    }

    public static boolean isCarverAlreadyApplied(final ChunkAccess chunk) {
        final Provider p = provider;
        return p != null && p.isCarverAlreadyApplied(chunk);
    }

    public static void recordStageTiming(final int stageOrdinal, final long elapsedNanos) {
        final Provider p = provider;
        if (p != null) {
            p.recordStageTiming(stageOrdinal, elapsedNanos);
        }
    }

    public static @Nullable Executor terrainContinuationExecutor() {
        final Provider p = provider;
        return p != null ? p.terrainContinuationExecutor() : null;
    }

    public static boolean tryAccelerateUndergroundOres(
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
        final Provider p = provider;
        return p != null && p.tryAccelerateUndergroundOres(
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

    public static @Nullable PrecomputedChunkLight tryComputeChunkLight(
        final ChunkAccess chunk,
        final Boolean[] emptySections,
        final boolean hasSkyLight
    ) {
        final Provider p = provider;
        return p != null ? p.tryComputeChunkLight(chunk, emptySections, hasSkyLight) : null;
    }

    public static @Nullable CompletableFuture<@Nullable PrecomputedChunkLight> tryComputeChunkLightAsync(
        final ChunkAccess chunk,
        final Boolean[] emptySections,
        final boolean hasSkyLight
    ) {
        final Provider p = provider;
        return p != null ? p.tryComputeChunkLightAsync(chunk, emptySections, hasSkyLight) : null;
    }

    public static void recordFreshChunkGenerated(final ServerLevel level, final ChunkAccess chunk) {
        final Provider p = provider;
        if (p != null) {
            p.recordFreshChunkGenerated(level, chunk);
        }
    }

    public static boolean shouldSkipColdChunkSave(final ChunkAccess chunk) {
        final Provider p = provider;
        return p != null && p.shouldSkipColdChunkSave(chunk);
    }

    public static boolean shouldIgnoreNaturalTickModification() {
        final Provider p = provider;
        return p != null && p.shouldIgnoreNaturalTickModification();
    }

    public static void enterNaturalTick() {
        final Provider p = provider;
        if (p != null) {
            p.enterNaturalTick();
        }
    }

    public static void exitNaturalTick() {
        final Provider p = provider;
        if (p != null) {
            p.exitNaturalTick();
        }
    }
}
