package dev.leafcraft.goldleaf.telemetry;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 zero-external-dependency per-chunk / per-region tick telemetry registry and accumulator
 * ({@code goldleaf-ADR-006} §1.1–§1.2).
 *
 * <p>When no listener is registered or {@code diagnostics.chunk-telemetry-spi} is {@code false},
 * {@link #isActive()} is {@code false} (a single predicted volatile read, {@code ~0 ns} idle cost
 * and zero object allocations).</p>
 */
public final class ChunkTickTelemetry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkTickTelemetry.class);

    private static final CopyOnWriteArrayList<RegionTickTelemetryListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean telemetryActive = false;
    private static final AtomicLong MASK_EPOCH = new AtomicLong(1L);

    private ChunkTickTelemetry() {
    }

    /**
     * Monotonic execution-mask epoch ({@code goldleaf-ADR-006}, {@code [DSN-1]}).
     * Bumped automatically whenever a telemetry consumer unregisters or is quarantined so stale
     * {@link ChunkExecutionMask} bits immediately evaluate as vanilla {@code 0} with zero
     * cross-region scheduling ({@code F-001}..{@code F-003}, {@code S-005}).
     */
    public static long currentMaskEpoch() {
        return MASK_EPOCH.get();
    }

    public static long invalidateAllExecutionMasks() {
        return MASK_EPOCH.incrementAndGet();
    }

    /**
     * Fast-path idle guard (~1 ns predicted branch when idle).
     */
    public static boolean isActive() {
        return telemetryActive;
    }

    public static int listenerCount() {
        return LISTENERS.size();
    }

    /**
     * Registers a telemetry listener subject to {@code diagnostics.chunk-telemetry-spi} and the
     * bounded {@code diagnostics.chunk-telemetry-max-listeners} cap.
     *
     * @return {@code true} if registered, {@code false} if SPI is disabled, already registered, or cap reached
     */
    public static synchronized boolean registerListener(final RegionTickTelemetryListener listener) {
        Objects.requireNonNull(listener, "listener");
        final GoldLeafConfig.Diagnostics diag = GoldLeafConfig.get().diagnostics;
        if (!diag.chunkTelemetrySpi) {
            return false;
        }
        if (LISTENERS.contains(listener)) {
            return false;
        }
        if (LISTENERS.size() >= diag.chunkTelemetryMaxListeners) {
            LOGGER.warn(
                "GoldLeaf ChunkTickTelemetry rejected listener {}: cap of {} reached",
                listener.getClass().getName(),
                diag.chunkTelemetryMaxListeners
            );
            return false;
        }
        LISTENERS.add(listener);
        telemetryActive = !LISTENERS.isEmpty();
        return true;
    }

    public static synchronized boolean unregisterListener(final RegionTickTelemetryListener listener) {
        if (listener == null) {
            return false;
        }
        final boolean removed = LISTENERS.remove(listener);
        telemetryActive = !LISTENERS.isEmpty();
        if (removed) {
            MASK_EPOCH.incrementAndGet();
        }
        return removed;
    }

    public static synchronized void clearAllListeners() {
        LISTENERS.clear();
        telemetryActive = false;
        MASK_EPOCH.incrementAndGet();
    }

    /**
     * Dispatches a single phase sample to all registered listeners. Any listener that throws is
     * logged once at {@code WARNING} and immediately quarantined (unregistered) so a faulty plugin
     * never crashes a region tick thread ({@code S-004}).
     */
    public static void dispatchPhaseSample(
        final long regionId,
        final String worldName,
        final int chunkX,
        final int chunkZ,
        final ChunkTickPhase phase,
        final long durationNanos
    ) {
        if (!telemetryActive) {
            return;
        }
        for (final RegionTickTelemetryListener listener : LISTENERS) {
            try {
                listener.onChunkPhaseSample(regionId, worldName, chunkX, chunkZ, phase, durationNanos);
            } catch (final Throwable t) {
                LOGGER.warn(
                    "Quarantining faulty GoldLeaf RegionTickTelemetryListener {} after exception",
                    listener.getClass().getName(),
                    t
                );
                unregisterListener(listener);
            }
        }
    }

    /**
     * Drains and resets the lazily allocated {@link ChunkActivityCounters} on {@code chunk}, or
     * returns {@code null} if the chunk had no recorded activity.
     */
    public static ChunkActivityCounters.@Nullable Snapshot drainChunkActivity(final LevelChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        final io.papermc.paper.spi.ChunkActivityCounters counters = chunk.paper$getActivityCountersIfPresent();
        if (counters == null) {
            return null;
        }
        final io.papermc.paper.spi.ChunkActivityCounters.Snapshot snap = counters.drainSnapshot();
        return new ChunkActivityCounters.Snapshot(
            snap.redstoneUpdates(),
            snap.hopperTransfers(),
            snap.pistonMoves(),
            snap.blockGrowths(),
            snap.entityTicks()
        );
    }

    /**
     * Per-region phase duration accumulator attached to {@code RegionizedWorldData} and cleared on
     * region pool reset ({@code goldleaf-ADR-003} §2, {@code goldleaf-ADR-006} §1.1).
     */
    public static final class RegionAccumulator {

        private final long[] phaseNanos = new long[ChunkTickPhase.values().length];

        public void addPhaseNanos(final ChunkTickPhase phase, final long nanos) {
            if (nanos > 0L) {
                this.phaseNanos[phase.ordinal()] += nanos;
            }
        }

        public long getPhaseNanos(final ChunkTickPhase phase) {
            return this.phaseNanos[phase.ordinal()];
        }

        public void reset() {
            java.util.Arrays.fill(this.phaseNanos, 0L);
        }
    }
}
