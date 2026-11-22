package io.papermc.paper.spi;

import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate per-chunk activity telemetry and execution-mask SPI.
 *
 * <p>Zero product or config coupling: idle cost when no provider is registered is a single
 * volatile check.</p>
 */
public final class PaperTelemetrySpi {

    public static final int MASK_NONE = 0;
    public static final int SUPPRESS_REDSTONE = 1 << 0;
    public static final int SUPPRESS_HOPPERS = 1 << 1;
    public static final int SUPPRESS_PISTONS = 1 << 2;
    public static final int DAMPEN_ENTITY_AI = 1 << 3;
    public static final int VIRTUALIZED_BLACKBOX = 1 << 4;

    public static final int PHASE_ENTITY = 0;
    public static final int PHASE_BLOCK_ENTITY = 1;
    public static final int PHASE_SCHEDULED_TICK = 2;
    public static final int PHASE_RANDOM_TICK = 3;
    public static final int PHASE_REDSTONE = 4;
    public static final int PHASE_HOPPER = 5;

    public interface Provider {
        default boolean isTelemetryActive() {
            return false;
        }

        default boolean isExecutionSuppressed(final @Nullable LevelChunk chunk, final int maskBit) {
            return false;
        }

        default void dispatchPhaseSample(
            final long regionId,
            final String worldName,
            final int chunkX,
            final int chunkZ,
            final int phaseOrdinal,
            final long durationNanos
        ) {
        }

        default long currentMaskEpoch() {
            return 1L;
        }

        default void onRegionTickEnd(
            final long regionId,
            final String worldId,
            final long durationNanos,
            final int playerCount,
            final int chunkCount
        ) {
        }
    }

    private static volatile @Nullable Provider provider;

    private PaperTelemetrySpi() {
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

    public static boolean isTelemetryActive() {
        final Provider p = provider;
        return p != null && p.isTelemetryActive();
    }

    public static boolean isExecutionSuppressed(final @Nullable LevelChunk chunk, final int maskBit) {
        final Provider p = provider;
        return p != null && p.isExecutionSuppressed(chunk, maskBit);
    }

    public static void dispatchPhaseSample(
        final long regionId,
        final String worldName,
        final int chunkX,
        final int chunkZ,
        final int phaseOrdinal,
        final long durationNanos
    ) {
        final Provider p = provider;
        if (p != null) {
            p.dispatchPhaseSample(regionId, worldName, chunkX, chunkZ, phaseOrdinal, durationNanos);
        }
    }

    public static long currentMaskEpoch() {
        final Provider p = provider;
        return p != null ? p.currentMaskEpoch() : 1L;
    }

    public static void onRegionTickEnd(
        final long regionId,
        final String worldId,
        final long durationNanos,
        final int playerCount,
        final int chunkCount
    ) {
        final Provider p = provider;
        if (p != null) {
            p.onRegionTickEnd(regionId, worldId, durationNanos, playerCount, chunkCount);
        }
    }

    public static final class RegionAccumulator {
        private final long[] phaseNanos = new long[8];

        public void addPhaseNanos(final int phaseOrdinal, final long nanos) {
            if (nanos > 0L && phaseOrdinal >= 0 && phaseOrdinal < this.phaseNanos.length) {
                this.phaseNanos[phaseOrdinal] += nanos;
            }
        }

        public long getPhaseNanos(final int phaseOrdinal) {
            return (phaseOrdinal >= 0 && phaseOrdinal < this.phaseNanos.length) ? this.phaseNanos[phaseOrdinal] : 0L;
        }

        public void reset() {
            java.util.Arrays.fill(this.phaseNanos, 0L);
        }
    }
}
