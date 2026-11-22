package dev.leafcraft.goldleaf.security;

import java.util.UUID;

/**
 * Layer 1 SPI for adaptive per-player chunk-generation backpressure hooked into
 * {@code RegionizedPlayerChunkLoader} ({@code goldleaf-ADR-012} §1.1).
 *
 * <p><strong>Legitimate-Flight Invariant:</strong> When the world's pending generation queue is
 * below the saturation watermark, normal elytra flight and exploration run at {@code 100%}
 * unthrottled server view distance and generation rate. Existing-chunk disk reads are never
 * penalized as generation debt.</p>
 */
public interface ChunkLoadBackpressurePolicy {

    /**
     * Effective pacing decision returned for a single player's chunk loader tick.
     */
    record PacingDecision(
        long maxGenerationsThisTick,
        int effectiveSendDistance,
        boolean backpressureActive
    ) {
    }

    /**
     * Records that {@code playerId} triggered {@code newlyGeneratedChunks} actual ungenerated-chunk
     * synthesis operations (distinct from pre-generated disk loads).
     */
    void recordNewChunkGenerations(UUID playerId, int newlyGeneratedChunks, long nowNanos);

    /**
     * Evaluates effective generation-rate and send-distance pacing for {@code playerId}.
     */
    PacingDecision evaluate(
        UUID playerId,
        int pendingGlobalGenQueue,
        long baseMaxGenerations,
        int baseSendDistance,
        long nowNanos
    );

    /**
     * Cleans up per-player generation-debt state on disconnect ({@code S-002}).
     */
    void onPlayerQuit(UUID playerId);

    static ChunkLoadBackpressurePolicy get() {
        return Holder.current;
    }

    static void register(final ChunkLoadBackpressurePolicy policy) {
        Holder.current = java.util.Objects.requireNonNull(policy, "policy");
    }

    static void resetDefault() {
        Holder.current = AdaptiveChunkGenBackpressure.DEFAULT;
    }

    final class Holder {
        private static volatile ChunkLoadBackpressurePolicy current = AdaptiveChunkGenBackpressure.DEFAULT;

        private Holder() {
        }
    }
}
