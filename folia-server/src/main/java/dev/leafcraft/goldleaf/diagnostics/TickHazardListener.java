package dev.leafcraft.goldleaf.diagnostics;

import org.jspecify.annotations.Nullable;

/**
 * Listener SPI for opt-in {@code O(1)} tick-thread runtime hazard notifications
 * ({@code goldleaf-ADR-007} §1.2).
 *
 * <p>Callbacks must remain strictly non-blocking and perform zero I/O ({@code S-005}). Any
 * uncaught exception is logged once at {@code WARNING} and automatically quarantines (unregisters)
 * the faulty listener ({@code S-004}).</p>
 */
public interface TickHazardListener {

    default void onSyncChunkLoad(
        final String worldName,
        final int chunkX,
        final int chunkZ,
        final @Nullable String callerClass
    ) {
        // default no-op
    }

    default void onTickStall(
        final long regionId,
        final long durationNanos,
        final String tickPhase
    ) {
        // default no-op
    }

    default void onWrongRegionAccess(
        final String reason,
        final String context
    ) {
        // default no-op
    }
}
