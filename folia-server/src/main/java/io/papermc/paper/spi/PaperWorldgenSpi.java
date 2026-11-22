package io.papermc.paper.spi;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate worldgen acceleration SPI for {@code ChunkStatusTasks} and
 * {@code NoiseBasedChunkGenerator}.
 *
 * <p>Zero product or config coupling: returns {@code false} when no Layer 2 accelerator is
 * registered so vanilla chunk generation executes untouched.</p>
 */
public final class PaperWorldgenSpi {

    @FunctionalInterface
    public interface DebugStateTransform {
        BlockState apply(NoiseChunk noiseChunk, int posX, int posY, int posZ, BlockState state);
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
}
