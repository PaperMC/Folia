package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in {@code O(1)} tick-thread runtime hazard monitor ({@code goldleaf-ADR-007} §1.2).
 *
 * <p>Gated by a single {@code volatile boolean hazardHooksActive} flag ({@code false} by default in
 * production for {@code ~0 ns} idle overhead). When enabled via
 * {@code diagnostics.runtime-hazard-hooks: true} (or {@link #setActive(boolean)} in tests/QA),
 * records exact {@code SYNC_CHUNK_LOAD}, {@code TICK_STALL}, and {@code WRONG_REGION_ACCESS}
 * counts and dispatches non-blocking callbacks to registered {@link TickHazardListener}s.</p>
 */
public final class TickHazardMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickHazardMonitor.class);
    private static final StackWalker CALLER_WALKER = StackWalker.getInstance();

    private static final CopyOnWriteArrayList<TickHazardListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final LongAdder SYNC_CHUNK_LOADS = new LongAdder();
    private static final LongAdder TICK_STALLS = new LongAdder();
    private static final LongAdder WRONG_REGION_ACCESSES = new LongAdder();

    private static volatile boolean hazardHooksActive = false;

    private TickHazardMonitor() {
    }

    /**
     * Synchronizes {@code hazardHooksActive} with {@link GoldLeafConfig.Diagnostics#runtimeHazardHooks}.
     */
    public static void refreshFromConfig() {
        hazardHooksActive = GoldLeafConfig.get().diagnostics.runtimeHazardHooks;
    }

    /**
     * Fast-path idle guard (~1 ns predicted branch when idle).
     */
    public static boolean isActive() {
        return hazardHooksActive;
    }

    public static void setActive(final boolean active) {
        hazardHooksActive = active;
    }

    public static void registerListener(final TickHazardListener listener) {
        Objects.requireNonNull(listener, "listener");
        LISTENERS.addIfAbsent(listener);
    }

    public static boolean unregisterListener(final TickHazardListener listener) {
        return listener != null && LISTENERS.remove(listener);
    }

    public static void clearListeners() {
        LISTENERS.clear();
    }

    public static boolean hasActiveListeners() {
        return !LISTENERS.isEmpty();
    }

    public static long syncChunkLoadCount() {
        return SYNC_CHUNK_LOADS.sum();
    }

    public static long tickStallCount() {
        return TICK_STALLS.sum();
    }

    public static long wrongRegionAccessCount() {
        return WRONG_REGION_ACCESSES.sum();
    }

    public static void resetCounters() {
        SYNC_CHUNK_LOADS.reset();
        TICK_STALLS.reset();
        WRONG_REGION_ACCESSES.reset();
    }

    public static void recordSyncChunkLoad(
        final String worldName,
        final int chunkX,
        final int chunkZ,
        final @Nullable String callerClass
    ) {
        if (!hazardHooksActive) {
            return;
        }
        SYNC_CHUNK_LOADS.increment();
        if (LISTENERS.isEmpty()) {
            return;
        }
        final String resolvedCaller = callerClass != null ? callerClass : resolveExternalCallerClass();
        for (final TickHazardListener listener : LISTENERS) {
            try {
                listener.onSyncChunkLoad(worldName, chunkX, chunkZ, resolvedCaller);
            } catch (final Throwable t) {
                quarantineListener(listener, t);
            }
        }
    }

    static @Nullable String resolveExternalCallerClass() {
        return CALLER_WALKER.walk(frames -> frames
            .map(StackWalker.StackFrame::getClassName)
            .filter(name -> !name.startsWith("dev.leafcraft.goldleaf.diagnostics.TickHazardMonitor")
                && !name.startsWith("io.papermc.paper.spi.PaperDiagnosticsSpi")
                && !name.startsWith("net.minecraft.")
                && !name.startsWith("ca.spottedleaf.")
                && !name.startsWith("org.bukkit.craftbukkit.")
                && !name.startsWith("java."))
            .findFirst()
            .orElse(null));
    }

    public static void recordTickIfStalled(
        final long regionId,
        final long durationNanos,
        final String tickPhase
    ) {
        if (!hazardHooksActive) {
            return;
        }
        final long thresholdNanos = TimeUnit.MILLISECONDS.toNanos(
            GoldLeafConfig.get().diagnostics.runtimeHazardStallThresholdMs
        );
        if (durationNanos < thresholdNanos) {
            return;
        }
        TICK_STALLS.increment();
        for (final TickHazardListener listener : LISTENERS) {
            try {
                listener.onTickStall(regionId, durationNanos, tickPhase);
            } catch (final Throwable t) {
                quarantineListener(listener, t);
            }
        }
    }

    public static void recordWrongRegionAccess(final String reason, final String context) {
        if (!hazardHooksActive) {
            return;
        }
        WRONG_REGION_ACCESSES.increment();
        for (final TickHazardListener listener : LISTENERS) {
            try {
                listener.onWrongRegionAccess(reason, context);
            } catch (final Throwable t) {
                quarantineListener(listener, t);
            }
        }
    }

    private static void quarantineListener(final TickHazardListener listener, final Throwable t) {
        LOGGER.warn(
            "Quarantining faulty GoldLeaf TickHazardListener {} after exception",
            listener.getClass().getName(),
            t
        );
        LISTENERS.remove(listener);
    }
}
