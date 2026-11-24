package dev.leafcraft.goldleaf.diagnostics;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 non-blocking diagnostic contributor SPI invoked by {@code FoliaWatchdogThread}
 * immediately before {@code WatchdogThread.dumpThread(...)} ({@code goldleaf-ADR-011} §1.1).
 *
 * <p><strong>Lock-Free Contract ({@code S-005}):</strong> Contributors execute on the watchdog
 * thread while the target region thread is stalled. Implementations MUST be strictly lock-free /
 * snapshot-only (or {@code tryLock()} with immediate bailout) and must never acquire region locks
 * or perform blocking I/O. Every contributor is wrapped in fail-closed exception isolation
 * ({@code S-004}) so a diagnostic failure can never suppress the base stack trace.</p>
 */
@FunctionalInterface
public interface WatchdogContextContributor {

    /**
     * Immutable lock-free snapshot of a stalled region tick passed to contributors.
     */
    record StalledTickContext(
        String worldName,
        int centerChunkX,
        int centerChunkZ,
        double elapsedSeconds,
        String threadName,
        boolean globalRegion,
        @Nullable StackTraceElement[] stackTrace,
        int minChunkX,
        int maxChunkX,
        int minChunkZ,
        int maxChunkZ,
        int loadedChunkCount
    ) {
        public StalledTickContext(
            final String worldName,
            final int centerChunkX,
            final int centerChunkZ,
            final double elapsedSeconds,
            final String threadName,
            final boolean globalRegion,
            final @Nullable StackTraceElement[] stackTrace
        ) {
            this(
                worldName,
                centerChunkX,
                centerChunkZ,
                elapsedSeconds,
                threadName,
                globalRegion,
                stackTrace,
                centerChunkX,
                centerChunkX,
                centerChunkZ,
                centerChunkZ,
                -1
            );
        }
    }

    /**
     * Produces zero or more diagnostic lines to append to the watchdog report before the raw
     * thread dump. Returning {@code null} or an empty list contributes nothing.
     */
    @Nullable List<String> contribute(StalledTickContext context);
}
