package dev.leafcraft.goldleaf.telemetry;

/**
 * Per-chunk / per-region simulation tick phases reported by {@link ChunkTickTelemetry}
 * ({@code goldleaf-ADR-006}).
 */
public enum ChunkTickPhase {
    ENTITY,
    BLOCK_ENTITY,
    SCHEDULED_TICK,
    RANDOM_TICK,
    REDSTONE,
    HOPPER
}
