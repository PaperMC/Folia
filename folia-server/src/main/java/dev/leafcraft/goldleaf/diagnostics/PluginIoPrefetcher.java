package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded off-thread OS page-cache prefetcher for {@code plugins/*.jar} and
 * {@code plugins/*&#47;*.yml} ({@code goldleaf-ADR-016} Layer C).
 *
 * <p>Uses sequential {@link InputStream} reads with {@code try-with-resources} (never
 * {@code FileChannel.map}) on daemon workers ({@code S-005}) so file descriptors are closed
 * immediately and zero plugin classes are loaded. Must be drained before {@code loadPlugins()}
 * executes so no worker holds open file handles during single-threaded plugin initialization
 * ({@code F-001}).</p>
 */
public final class PluginIoPrefetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginIoPrefetcher.class);
    private static final int READ_BUFFER_BYTES = 64 * 1024;
    private static final long DEFAULT_DRAIN_TIMEOUT_MS = 250L;

    public record PrefetchStats(
        int filesPrefetched,
        long bytesPrefetched,
        int filesSkippedCap,
        int errorCount,
        long elapsedNanos,
        boolean cancelled
    ) {
        public static final PrefetchStats EMPTY = new PrefetchStats(0, 0L, 0, 0, 0L, false);
    }

    public static final class PrefetchHandle implements AutoCloseable {
        private final ExecutorService executor;
        private final AtomicBoolean cancelled;
        private final AtomicInteger filesPrefetched;
        private final AtomicLong bytesPrefetched;
        private final AtomicInteger filesSkippedCap;
        private final AtomicInteger errorCount;
        private final long startNanos;
        private final AtomicReference<PrefetchStats> finalStats = new AtomicReference<>();

        private PrefetchHandle(
            final ExecutorService executor,
            final AtomicBoolean cancelled,
            final AtomicInteger filesPrefetched,
            final AtomicLong bytesPrefetched,
            final AtomicInteger filesSkippedCap,
            final AtomicInteger errorCount,
            final long startNanos
        ) {
            this.executor = executor;
            this.cancelled = cancelled;
            this.filesPrefetched = filesPrefetched;
            this.bytesPrefetched = bytesPrefetched;
            this.filesSkippedCap = filesSkippedCap;
            this.errorCount = errorCount;
            this.startNanos = startNanos;
        }

        static PrefetchHandle completed(final PrefetchStats stats) {
            final PrefetchHandle handle = new PrefetchHandle(
                null,
                new AtomicBoolean(false),
                new AtomicInteger(stats.filesPrefetched()),
                new AtomicLong(stats.bytesPrefetched()),
                new AtomicInteger(stats.filesSkippedCap()),
                new AtomicInteger(stats.errorCount()),
                System.nanoTime()
            );
            handle.finalStats.set(stats);
            return handle;
        }

        /**
         * Signals remaining prefetch tasks to stop, waits up to {@code timeoutMs} for active
         * workers to close their file descriptors, and returns final statistics.
         */
        public PrefetchStats drainAndStop(final long timeoutMs) {
            final PrefetchStats cached = this.finalStats.get();
            if (cached != null) {
                return cached;
            }
            final boolean wasRunning = this.executor != null && !this.executor.isTerminated();
            if (this.executor != null) {
                this.executor.shutdown();
                boolean terminated = false;
                try {
                    terminated = this.executor.awaitTermination(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (!terminated) {
                    this.cancelled.set(true);
                    this.executor.shutdownNow();
                    try {
                        this.executor.awaitTermination(100L, TimeUnit.MILLISECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            final PrefetchStats snapshot = new PrefetchStats(
                this.filesPrefetched.get(),
                this.bytesPrefetched.get(),
                this.filesSkippedCap.get(),
                this.errorCount.get(),
                Math.max(0L, System.nanoTime() - this.startNanos),
                this.cancelled.get() && wasRunning
            );
            this.finalStats.compareAndSet(null, snapshot);
            lastStats = this.finalStats.get();
            return lastStats;
        }

        public PrefetchStats awaitCompletion(final long timeoutMs) {
            return this.drainAndStop(timeoutMs);
        }

        @Override
        public void close() {
            this.drainAndStop(DEFAULT_DRAIN_TIMEOUT_MS);
        }
    }

    private static final AtomicReference<PrefetchHandle> GLOBAL_HANDLE = new AtomicReference<>();
    private static volatile PrefetchStats lastStats = PrefetchStats.EMPTY;

    private PluginIoPrefetcher() {
    }

    public static PrefetchStats lastStats() {
        return lastStats;
    }

    public static void startGlobalAsync(final Path pluginsDir) {
        final GoldLeafConfig.Diagnostics diag = GoldLeafConfig.get().diagnostics;
        if (!diag.pluginIoPrefetchEnabled) {
            return;
        }
        final PrefetchHandle handle = startAsync(
            pluginsDir,
            diag.pluginIoPrefetchThreads,
            diag.pluginIoPrefetchMaxFileBytes,
            diag.pluginIoPrefetchMaxTotalBytes
        );
        final PrefetchHandle previous = GLOBAL_HANDLE.getAndSet(handle);
        if (previous != null) {
            previous.close();
        }
    }

    public static PrefetchStats drainGlobalBeforePluginLoad() {
        final PrefetchHandle handle = GLOBAL_HANDLE.getAndSet(null);
        if (handle == null) {
            return lastStats;
        }
        return handle.drainAndStop(DEFAULT_DRAIN_TIMEOUT_MS);
    }

    public static PrefetchHandle startAsync(
        final Path pluginsDir,
        final int configuredThreads,
        final long maxFileBytes,
        final long maxTotalBytes
    ) {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            lastStats = PrefetchStats.EMPTY;
            return PrefetchHandle.completed(PrefetchStats.EMPTY);
        }
        final long startNanos = System.nanoTime();
        final List<Path> targets = discoverTargets(pluginsDir);
        if (targets.isEmpty()) {
            lastStats = PrefetchStats.EMPTY;
            return PrefetchHandle.completed(PrefetchStats.EMPTY);
        }

        final int workers = Math.max(1, Math.min(configuredThreads, Runtime.getRuntime().availableProcessors()));
        final AtomicInteger threadSeq = new AtomicInteger(1);
        final ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            final Thread thread = new Thread(runnable, "GoldLeaf-PluginIoPrefetch-" + threadSeq.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicInteger filesPrefetched = new AtomicInteger();
        final AtomicLong bytesPrefetched = new AtomicLong();
        final AtomicInteger filesSkippedCap = new AtomicInteger();
        final AtomicInteger errorCount = new AtomicInteger();
        final long effectiveMaxFile = Math.max(1024L, maxFileBytes);
        final long effectiveMaxTotal = Math.max(effectiveMaxFile, maxTotalBytes);

        for (final Path target : targets) {
            executor.submit(() -> prefetchSingleFile(
                target,
                effectiveMaxFile,
                effectiveMaxTotal,
                cancelled,
                filesPrefetched,
                bytesPrefetched,
                filesSkippedCap,
                errorCount
            ));
        }

        return new PrefetchHandle(
            executor,
            cancelled,
            filesPrefetched,
            bytesPrefetched,
            filesSkippedCap,
            errorCount,
            startNanos
        );
    }

    static List<Path> discoverTargets(final Path pluginsDir) {
        final List<Path> results = new ArrayList<>();
        try (Stream<Path> topLevel = Files.list(pluginsDir)) {
            topLevel.forEach(entry -> {
                if (Files.isSymbolicLink(entry)) {
                    return;
                }
                if (Files.isRegularFile(entry) && isJar(entry)) {
                    results.add(entry);
                } else if (Files.isDirectory(entry)) {
                    try (Stream<Path> sub = Files.list(entry)) {
                        sub.filter(p -> !Files.isSymbolicLink(p) && Files.isRegularFile(p) && isYaml(p))
                            .forEach(results::add);
                    } catch (final IOException e) {
                        LOGGER.warn("GoldLeaf plugin I/O prefetch failed to list {}: {}", entry, e.getMessage());
                    }
                }
            });
        } catch (final IOException e) {
            LOGGER.warn("GoldLeaf plugin I/O prefetch failed to list {}: {}", pluginsDir, e.getMessage());
        }
        results.sort(Comparator.comparing(Path::toString));
        return results;
    }

    private static void prefetchSingleFile(
        final Path file,
        final long maxFileBytes,
        final long maxTotalBytes,
        final AtomicBoolean cancelled,
        final AtomicInteger filesPrefetched,
        final AtomicLong bytesPrefetched,
        final AtomicInteger filesSkippedCap,
        final AtomicInteger errorCount
    ) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            return;
        }
        if (bytesPrefetched.get() >= maxTotalBytes) {
            filesSkippedCap.incrementAndGet();
            return;
        }
        final byte[] buffer = new byte[READ_BUFFER_BYTES];
        long fileRead = 0L;
        try (InputStream in = Files.newInputStream(file)) {
            while (!cancelled.get() && !Thread.currentThread().isInterrupted()) {
                final long remainingFile = maxFileBytes - fileRead;
                final long remainingTotal = maxTotalBytes - bytesPrefetched.get();
                final long allowance = Math.min(remainingFile, remainingTotal);
                if (allowance <= 0L) {
                    if (fileRead == 0L) {
                        filesSkippedCap.incrementAndGet();
                    }
                    break;
                }
                final int toRead = (int) Math.min(buffer.length, allowance);
                final int n = in.read(buffer, 0, toRead);
                if (n < 0) {
                    break;
                }
                fileRead += n;
                bytesPrefetched.addAndGet(n);
            }
            if (fileRead > 0L) {
                filesPrefetched.incrementAndGet();
            }
        } catch (final IOException e) {
            errorCount.incrementAndGet();
            LOGGER.warn("GoldLeaf plugin I/O prefetch skipped {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private static boolean isJar(final Path path) {
        final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar");
    }

    private static boolean isYaml(final Path path) {
        final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
