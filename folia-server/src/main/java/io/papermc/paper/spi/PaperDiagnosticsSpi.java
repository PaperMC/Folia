package io.papermc.paper.spi;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Layer 1 upstream-candidate diagnostics, boot/shutdown telemetry, watchdog context, and plugin
 * preflight SPI.
 *
 * <p>Zero product or config coupling: all hooks are no-ops when no Layer 2 provider is registered.</p>
 */
public final class PaperDiagnosticsSpi {

    public static final String PHASE_BOOTSTRAP = "bootstrap";
    public static final String PHASE_DATAPACKS = "datapacks";
    public static final String PHASE_PLUGINS_LOAD = "plugins_load";
    public static final String PHASE_PLUGINS_ENABLE_STARTUP = "plugins_enable_startup";
    public static final String PHASE_LEVEL_LOAD = "level_load";
    public static final String PHASE_PLUGINS_ENABLE_POSTWORLD = "plugins_enable_postworld";
    public static final String PHASE_SHUTDOWN_PLUGINS_DISABLE = "shutdown_plugins_disable";
    public static final String PHASE_SHUTDOWN_IO_FLUSH = "shutdown_io_flush";

    public interface Provider {
        default boolean isHazardMonitorActive() {
            return false;
        }

        default void recordTickIfStalled(final long regionId, final long durationNanos, final String label) {
        }

        default void recordSyncChunkLoad(
            final String worldName,
            final int chunkX,
            final int chunkZ,
            final @Nullable String caller
        ) {
        }

        default void recordWrongRegionAccess(final String reason, final String context) {
        }

        default void emitWatchdogContext(
            final Logger logger,
            final String worldName,
            final int centerChunkX,
            final int centerChunkZ,
            final double totalElapsedSeconds,
            final String threadName,
            final boolean isGlobalRegion,
            final StackTraceElement @Nullable [] stackTrace
        ) {
        }

        default void markBootStart() {
        }

        default void startBootPhase(final String phase) {
        }

        default void endBootPhase(final String phase) {
        }

        default void recordPluginEnable(final String pluginName, final long durationNanos) {
        }

        default void logBootSummary() {
        }

        default void markShutdownStart() {
        }

        default void logShutdownSummaryAndAudit() {
        }

        default void startPluginIoPrefetch(final Path pluginsDir) {
        }

        default void drainPluginIoPrefetch() {
        }

        default void runPluginPreflight(final List<Path> archives) {
        }
    }

    private static volatile @Nullable Provider provider;

    private PaperDiagnosticsSpi() {
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

    public static boolean isHazardMonitorActive() {
        final Provider p = provider;
        return p != null && p.isHazardMonitorActive();
    }

    public static void recordTickIfStalled(final long regionId, final long durationNanos, final String label) {
        final Provider p = provider;
        if (p != null) {
            p.recordTickIfStalled(regionId, durationNanos, label);
        }
    }

    public static void recordSyncChunkLoad(
        final String worldName,
        final int chunkX,
        final int chunkZ,
        final @Nullable String caller
    ) {
        final Provider p = provider;
        if (p != null) {
            p.recordSyncChunkLoad(worldName, chunkX, chunkZ, caller);
        }
    }

    public static void recordWrongRegionAccess(final String reason, final String context) {
        final Provider p = provider;
        if (p != null) {
            p.recordWrongRegionAccess(reason, context);
        }
    }

    public static void emitWatchdogContext(
        final Logger logger,
        final String worldName,
        final int centerChunkX,
        final int centerChunkZ,
        final double totalElapsedSeconds,
        final String threadName,
        final boolean isGlobalRegion,
        final StackTraceElement @Nullable [] stackTrace
    ) {
        final Provider p = provider;
        if (p != null) {
            p.emitWatchdogContext(logger, worldName, centerChunkX, centerChunkZ, totalElapsedSeconds, threadName, isGlobalRegion, stackTrace);
        }
    }

    public static void markBootStart() {
        final Provider p = provider;
        if (p != null) {
            p.markBootStart();
        }
    }

    public static void startBootPhase(final String phase) {
        final Provider p = provider;
        if (p != null) {
            p.startBootPhase(phase);
        }
    }

    public static void endBootPhase(final String phase) {
        final Provider p = provider;
        if (p != null) {
            p.endBootPhase(phase);
        }
    }

    public static void recordPluginEnable(final String pluginName, final long durationNanos) {
        final Provider p = provider;
        if (p != null) {
            p.recordPluginEnable(pluginName, durationNanos);
        }
    }

    public static void logBootSummary() {
        final Provider p = provider;
        if (p != null) {
            p.logBootSummary();
        }
    }

    public static void markShutdownStart() {
        final Provider p = provider;
        if (p != null) {
            p.markShutdownStart();
        }
    }

    public static void logShutdownSummaryAndAudit() {
        final Provider p = provider;
        if (p != null) {
            p.logShutdownSummaryAndAudit();
        }
    }

    public static void startPluginIoPrefetch(final Path pluginsDir) {
        final Provider p = provider;
        if (p != null) {
            p.startPluginIoPrefetch(pluginsDir);
        }
    }

    public static void drainPluginIoPrefetch() {
        final Provider p = provider;
        if (p != null) {
            p.drainPluginIoPrefetch();
        }
    }

    public static void runPluginPreflight(final List<Path> archives) {
        final Provider p = provider;
        if (p != null) {
            p.runPluginPreflight(archives);
        }
    }
}
