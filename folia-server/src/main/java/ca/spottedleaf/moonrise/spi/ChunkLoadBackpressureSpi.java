package ca.spottedleaf.moonrise.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate per-player chunk generation backpressure policy SPI for
 * {@code RegionizedPlayerChunkLoader}.
 *
 * <p>Returns {@code baseMaxGens} unchanged when no Layer 2 policy is registered.</p>
 */
@FunctionalInterface
public interface ChunkLoadBackpressureSpi {

    long evaluate(
        UUID playerId,
        int pendingGenerations,
        long baseMaxGens,
        int sendDistance,
        long nowNanos
    );

    default int evaluateSendDistance(
        final UUID playerId,
        final int pendingGenerations,
        final long baseMaxGens,
        final int sendDistance,
        final long nowNanos
    ) {
        return sendDistance;
    }

    static int evaluateSendDistanceOrDefault(
        final UUID playerId,
        final int pendingGenerations,
        final long baseMaxGens,
        final int sendDistance,
        final long nowNanos
    ) {
        final ChunkLoadBackpressureSpi spi = Holder.current;
        return spi != null
            ? spi.evaluateSendDistance(playerId, pendingGenerations, baseMaxGens, sendDistance, nowNanos)
            : sendDistance;
    }

    default void recordNewChunkGenerations(final UUID playerId, final int generatedCount, final long nowNanos) {
    }

    static void recordNewChunkGenerationsIfPresent(final UUID playerId, final int generatedCount, final long nowNanos) {
        final ChunkLoadBackpressureSpi spi = Holder.current;
        if (spi != null) {
            spi.recordNewChunkGenerations(playerId, generatedCount, nowNanos);
        }
    }

    static long evaluateOrDefault(
        final UUID playerId,
        final int pendingGenerations,
        final long baseMaxGens,
        final int sendDistance,
        final long nowNanos
    ) {
        final ChunkLoadBackpressureSpi spi = Holder.current;
        return spi != null ? spi.evaluate(playerId, pendingGenerations, baseMaxGens, sendDistance, nowNanos) : baseMaxGens;
    }

    static @Nullable ChunkLoadBackpressureSpi get() {
        return Holder.current;
    }

    static void register(final @Nullable ChunkLoadBackpressureSpi spi) {
        Holder.current = spi;
    }

    static void resetDefault() {
        Holder.current = null;
    }

    final class Holder {
        private static volatile @Nullable ChunkLoadBackpressureSpi current;

        private Holder() {
        }
    }
}
