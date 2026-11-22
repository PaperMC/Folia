package dev.leafcraft.goldleaf.telemetry;

/**
 * Listener SPI receiving exact per-chunk / per-region tick phase timings from
 * {@link ChunkTickTelemetry} ({@code goldleaf-ADR-006} §1.1).
 *
 * <p>Callbacks run synchronously on the owning region thread and must remain non-blocking
 * ({@code S-005}). Any uncaught exception is logged once at {@code WARNING} and automatically
 * quarantines (unregisters) the faulty listener ({@code S-004}).</p>
 */
@FunctionalInterface
public interface RegionTickTelemetryListener {

    void onChunkPhaseSample(
        long regionId,
        String worldName,
        int chunkX,
        int chunkZ,
        ChunkTickPhase phase,
        long durationNanos
    );
}
