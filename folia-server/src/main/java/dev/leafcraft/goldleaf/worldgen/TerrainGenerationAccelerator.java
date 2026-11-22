package dev.leafcraft.goldleaf.worldgen;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 typed batch-dispatch SPI for MC {@code 26.3+} {@code ChunkStatus.BIOMES} and
 * {@code ChunkStatus.TERRAIN} acceleration ({@code goldleaf-ADR-004} §2).
 *
 * <p>Invoked strictly when no Bukkit {@code CustomChunkGenerator} is active and the chunk is not
 * debug void terrain. The default {@link #NO_OP} implementation delegates directly to stock
 * {@link NoiseBasedChunkGenerator} with zero overhead. When an external Layer 2 engine handles a
 * task, the caller must skip stock {@code doFill}/{@code createBiomes} (zero dual execution) and,
 * when {@code goldleaf.yml worldgen.require-accelerator} is {@code true}, any unaccelerated chunk
 * fails closed with {@link IllegalStateException} ({@code S-004}).</p>
 */
public interface TerrainGenerationAccelerator {

    Logger LOGGER = LoggerFactory.getLogger(TerrainGenerationAccelerator.class);

    enum ParityMode {
        VANILLA_EXACT,
        FAST;

        public static ParityMode fromConfig(final @Nullable String value) {
            if (value == null) {
                return VANILLA_EXACT;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "vanilla-exact" -> VANILLA_EXACT;
                case "fast" -> FAST;
                default -> throw new IllegalArgumentException(
                    "Unsupported worldgen parity mode '" + value + "' (expected 'vanilla-exact' or 'fast')"
                );
            };
        }
    }

    record BiomeTask(
        ServerLevel level,
        ChunkGenerator generator,
        RandomState randomState,
        Blender blender,
        StructureManager structureManager,
        ChunkAccess chunk
    ) {
        public BiomeTask {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(generator, "generator");
            Objects.requireNonNull(randomState, "randomState");
            Objects.requireNonNull(blender, "blender");
            Objects.requireNonNull(structureManager, "structureManager");
            Objects.requireNonNull(chunk, "chunk");
        }
    }

    record TerrainTask(
        ServerLevel level,
        NoiseBasedChunkGenerator generator,
        DensityVolume volume,
        NoiseRouter noiseRouter,
        NoiseGeneratorSettings settings,
        RandomState randomState,
        @Nullable Beardifier beardifier,
        Blender blender,
        ChunkAccess chunk
    ) {
        public TerrainTask {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(generator, "generator");
            Objects.requireNonNull(volume, "volume");
            Objects.requireNonNull(noiseRouter, "noiseRouter");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(randomState, "randomState");
            Objects.requireNonNull(blender, "blender");
            Objects.requireNonNull(chunk, "chunk");
        }
    }

    record WaveResult(
        boolean[] handled,
        long gpuDispatchNanos,
        long readbackNanos,
        long hostCpuNanos
    ) {
        public WaveResult {
            Objects.requireNonNull(handled, "handled");
            handled = Arrays.copyOf(handled, handled.length);
            if (gpuDispatchNanos < 0L || readbackNanos < 0L || hostCpuNanos < 0L) {
                throw new IllegalArgumentException("WaveResult timing values must be >= 0");
            }
        }

        public boolean isHandled(final int index) {
            return index >= 0 && index < this.handled.length && this.handled[index];
        }

        public int handledCount() {
            int count = 0;
            for (final boolean value : this.handled) {
                if (value) {
                    count++;
                }
            }
            return count;
        }

        public static WaveResult noneHandled(final int taskCount) {
            if (taskCount < 0) {
                throw new IllegalArgumentException("taskCount must be >= 0, got " + taskCount);
            }
            return new WaveResult(new boolean[taskCount], 0L, 0L, 0L);
        }

        public static WaveResult allHandled(
            final int taskCount,
            final long gpuDispatchNanos,
            final long readbackNanos,
            final long hostCpuNanos
        ) {
            if (taskCount < 0) {
                throw new IllegalArgumentException("taskCount must be >= 0, got " + taskCount);
            }
            final boolean[] flags = new boolean[taskCount];
            Arrays.fill(flags, true);
            return new WaveResult(flags, gpuDispatchNanos, readbackNanos, hostCpuNanos);
        }
    }

    TerrainGenerationAccelerator NO_OP = new TerrainGenerationAccelerator() {
        @Override
        public WaveResult accelerateBiomes(final WorldGenContext context, final List<BiomeTask> tasks) {
            return WaveResult.noneHandled(tasks.size());
        }

        @Override
        public WaveResult accelerateTerrain(final @Nullable WorldGenContext context, final List<TerrainTask> tasks) {
            return WaveResult.noneHandled(tasks.size());
        }

        @Override
        public boolean isNoOp() {
            return true;
        }
    };

    WaveResult accelerateBiomes(WorldGenContext context, List<BiomeTask> tasks);

    WaveResult accelerateTerrain(@Nullable WorldGenContext context, List<TerrainTask> tasks);

    default boolean isNoOp() {
        return false;
    }

    default ParityMode parityMode() {
        final GoldLeafConfig config = GoldLeafConfig.get();
        if (config == null || config.worldgen == null) {
            return ParityMode.VANILLA_EXACT;
        }
        return ParityMode.fromConfig(config.worldgen.parityMode);
    }

    static TerrainGenerationAccelerator get() {
        return Holder.current;
    }

    static boolean isActive() {
        return !Holder.current.isNoOp();
    }

    static void register(final @Nullable TerrainGenerationAccelerator accelerator) {
        Holder.current = accelerator != null ? accelerator : NO_OP;
    }

    static void resetDefault() {
        Holder.current = NO_OP;
    }

    /**
     * Dispatches a single {@code BIOMES} task through the active accelerator and records wave
     * provenance telemetry. Returns {@code true} if the accelerator populated the chunk's biomes
     * (caller must skip stock {@code createBiomes}); throws {@link IllegalStateException} when
     * {@code requireAccelerator} is {@code true} and the task was not accelerated ({@code S-004}).
     */
    static boolean dispatchSingleBiome(
        final WorldGenContext context,
        final BiomeTask task,
        final boolean requireAccelerator,
        final WorldgenWaveTelemetry telemetry
    ) {
        final TerrainGenerationAccelerator accelerator = get();
        if (accelerator.isNoOp()) {
            if (requireAccelerator) {
                throw new IllegalStateException(
                    "GoldLeaf worldgen.require-accelerator is true, but no TerrainGenerationAccelerator is registered for BIOMES"
                );
            }
            return false;
        }
        final WaveResult result;
        try {
            result = accelerator.accelerateBiomes(context, List.of(task));
        } catch (final RuntimeException ex) {
            LOGGER.warn("GoldLeaf BIOMES accelerator failed for chunk {}", task.chunk().getPos(), ex);
            if (requireAccelerator) {
                throw new IllegalStateException(
                    "GoldLeaf BIOMES accelerator failed under strict require-accelerator mode for chunk "
                        + task.chunk().getPos(),
                    ex
                );
            }
            telemetry.recordBiomeWave(1, 0, 0L, 0L, 0L);
            return false;
        }
        final boolean handled = result != null && result.isHandled(0);
        telemetry.recordBiomeWave(
            1,
            handled ? 1 : 0,
            result != null ? result.gpuDispatchNanos() : 0L,
            result != null ? result.readbackNanos() : 0L,
            result != null ? result.hostCpuNanos() : 0L
        );
        if (!handled && requireAccelerator) {
            throw new IllegalStateException(
                "GoldLeaf BIOMES accelerator declined chunk " + task.chunk().getPos()
                    + " under strict require-accelerator mode"
            );
        }
        return handled;
    }

    /**
     * Dispatches a single {@code TERRAIN} task through the active accelerator and records wave
     * provenance telemetry. Returns {@code true} if the accelerator filled the chunk's terrain
     * sections (caller must skip stock {@code doFill}); throws {@link IllegalStateException} when
     * {@code requireAccelerator} is {@code true} and the task was not accelerated ({@code S-004}).
     */
    static boolean dispatchSingleTerrain(
        final @Nullable WorldGenContext context,
        final TerrainTask task,
        final boolean requireAccelerator,
        final WorldgenWaveTelemetry telemetry
    ) {
        final TerrainGenerationAccelerator accelerator = get();
        if (accelerator.isNoOp()) {
            if (requireAccelerator) {
                throw new IllegalStateException(
                    "GoldLeaf worldgen.require-accelerator is true, but no TerrainGenerationAccelerator is registered for TERRAIN"
                );
            }
            return false;
        }
        final WaveResult result;
        try {
            result = accelerator.accelerateTerrain(context, List.of(task));
        } catch (final RuntimeException ex) {
            LOGGER.warn("GoldLeaf TERRAIN accelerator failed for chunk {}", task.chunk().getPos(), ex);
            if (requireAccelerator) {
                throw new IllegalStateException(
                    "GoldLeaf TERRAIN accelerator failed under strict require-accelerator mode for chunk "
                        + task.chunk().getPos(),
                    ex
                );
            }
            telemetry.recordTerrainWave(1, 0, 0L, 0L, 0L);
            return false;
        }
        final boolean handled = result != null && result.isHandled(0);
        telemetry.recordTerrainWave(
            1,
            handled ? 1 : 0,
            result != null ? result.gpuDispatchNanos() : 0L,
            result != null ? result.readbackNanos() : 0L,
            result != null ? result.hostCpuNanos() : 0L
        );
        if (!handled && requireAccelerator) {
            throw new IllegalStateException(
                "GoldLeaf TERRAIN accelerator declined chunk " + task.chunk().getPos()
                    + " under strict require-accelerator mode"
            );
        }
        return handled;
    }

    final class Holder {
        private static volatile TerrainGenerationAccelerator current = NO_OP;

        private Holder() {
        }
    }
}
