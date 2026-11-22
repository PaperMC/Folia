package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin-hook boot/shutdown waterfall telemetry and lingering non-daemon thread auditor
 * ({@code goldleaf-ADR-016} Layer B).
 *
 * <p>Records monotonic nanosecond spans for major server lifecycle phases and per-plugin
 * {@code onEnable} durations without altering Bukkit/Folia thread-confinement semantics
 * ({@code F-001}). At the end of {@code MinecraftServer#stopPart2()}, audits lingering non-daemon
 * threads that may delay JVM exit ({@code S-004}, {@code goldleaf-ADR-011}).</p>
 */
public final class BootShutdownTelemetry {

    private static final Logger LOGGER = LoggerFactory.getLogger(BootShutdownTelemetry.class);

    public static final String PHASE_BOOTSTRAP = "bootstrap";
    public static final String PHASE_DATAPACKS = "datapacks";
    public static final String PHASE_PLUGINS_LOAD = "plugins-load";
    public static final String PHASE_PLUGINS_ENABLE_STARTUP = "plugins-enable-startup";
    public static final String PHASE_LEVEL_LOAD = "level-load";
    public static final String PHASE_PLUGINS_ENABLE_POSTWORLD = "plugins-enable-postworld";
    public static final String PHASE_TOTAL_BOOT = "total-boot";

    public static final String PHASE_SHUTDOWN_PLUGINS_DISABLE = "plugins-disable";
    public static final String PHASE_SHUTDOWN_IO_FLUSH = "io-flush";
    public static final String PHASE_SHUTDOWN_TOTAL = "total-shutdown";

    private static final List<String> BOOT_PHASE_ORDER = List.of(
        PHASE_BOOTSTRAP,
        PHASE_DATAPACKS,
        PHASE_PLUGINS_LOAD,
        PHASE_PLUGINS_ENABLE_STARTUP,
        PHASE_LEVEL_LOAD,
        PHASE_PLUGINS_ENABLE_POSTWORLD,
        PHASE_TOTAL_BOOT
    );

    private static final List<String> SHUTDOWN_PHASE_ORDER = List.of(
        PHASE_SHUTDOWN_PLUGINS_DISABLE,
        PHASE_SHUTDOWN_IO_FLUSH,
        PHASE_SHUTDOWN_TOTAL
    );

    private static final Set<String> IGNORED_JVM_THREADS = Set.of(
        "DestroyJavaVM",
        "Signal Dispatcher",
        "Reference Handler",
        "Finalizer",
        "Common-Cleaner",
        "Notification Thread",
        "Attach Listener",
        "Monitor Ctrl-Break",
        "process reaper",
        "Server thread",
        "Region shutdown thread"
    );

    private static final List<String> IGNORED_RUNTIME_THREAD_PREFIXES = List.of(
        "Craft Scheduler Thread - ",
        "Dimension-Data-IO-Worker-"
    );

    private static final int MAX_STACK_FRAMES = 3;
    private static final int MAX_REPORTED_SLOW_PLUGINS = 10;

    private static final BootShutdownTelemetry GLOBAL = new BootShutdownTelemetry(System::nanoTime);

    public record PluginTiming(String pluginName, long durationNanos) {
        public PluginTiming {
            Objects.requireNonNull(pluginName, "pluginName");
            if (durationNanos < 0L) {
                throw new IllegalArgumentException("durationNanos must be >= 0");
            }
        }

        public double durationMs() {
            return this.durationNanos / 1_000_000.0;
        }
    }

    public record LingeringThread(String name, Thread.State state, List<String> topFrames) {
        public LingeringThread {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(state, "state");
            topFrames = List.copyOf(topFrames);
        }
    }

    private final LongSupplier nanoClock;
    private final AtomicLong bootStartNanos = new AtomicLong(-1L);
    private final AtomicLong shutdownStartNanos = new AtomicLong(-1L);
    private final ConcurrentHashMap<String, Long> phaseStartNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> phaseDurationNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> pluginEnableNanos = new ConcurrentHashMap<>();

    public BootShutdownTelemetry(final LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public static BootShutdownTelemetry global() {
        return GLOBAL;
    }

    public void reset() {
        this.bootStartNanos.set(-1L);
        this.shutdownStartNanos.set(-1L);
        this.phaseStartNanos.clear();
        this.phaseDurationNanos.clear();
        this.pluginEnableNanos.clear();
    }

    public void markBootStart() {
        this.bootStartNanos.compareAndSet(-1L, this.nanoClock.getAsLong());
    }

    public void markBootComplete() {
        final long start = this.bootStartNanos.get();
        if (start >= 0L) {
            final long elapsed = Math.max(0L, this.nanoClock.getAsLong() - start);
            this.phaseDurationNanos.put(PHASE_TOTAL_BOOT, elapsed);
        }
    }

    public void markShutdownStart() {
        this.shutdownStartNanos.compareAndSet(-1L, this.nanoClock.getAsLong());
    }

    public void markShutdownComplete() {
        final long start = this.shutdownStartNanos.get();
        if (start >= 0L) {
            final long elapsed = Math.max(0L, this.nanoClock.getAsLong() - start);
            this.phaseDurationNanos.put(PHASE_SHUTDOWN_TOTAL, elapsed);
        }
    }

    public void startPhase(final String phase) {
        Objects.requireNonNull(phase, "phase");
        this.phaseStartNanos.put(phase, this.nanoClock.getAsLong());
    }

    public long endPhase(final String phase) {
        Objects.requireNonNull(phase, "phase");
        final Long start = this.phaseStartNanos.remove(phase);
        if (start == null) {
            return -1L;
        }
        final long elapsed = Math.max(0L, this.nanoClock.getAsLong() - start);
        this.phaseDurationNanos.put(phase, elapsed);
        return elapsed;
    }

    public long phaseDurationNanos(final String phase) {
        return this.phaseDurationNanos.getOrDefault(phase, -1L);
    }

    public void recordPluginEnable(final String pluginName, final long durationNanos) {
        if (pluginName == null || pluginName.isBlank()) {
            return;
        }
        this.pluginEnableNanos.merge(pluginName, Math.max(0L, durationNanos), Long::sum);
    }

    public List<PluginTiming> slowPlugins(final long thresholdMs) {
        final long thresholdNanos = Math.max(1L, thresholdMs) * 1_000_000L;
        final List<PluginTiming> slow = new ArrayList<>();
        for (final Map.Entry<String, Long> entry : this.pluginEnableNanos.entrySet()) {
            if (entry.getValue() >= thresholdNanos) {
                slow.add(new PluginTiming(entry.getKey(), entry.getValue()));
            }
        }
        slow.sort(Comparator.comparingLong(PluginTiming::durationNanos).reversed());
        if (slow.size() > MAX_REPORTED_SLOW_PLUGINS) {
            return List.copyOf(slow.subList(0, MAX_REPORTED_SLOW_PLUGINS));
        }
        return List.copyOf(slow);
    }

    public String formatBootSummary(final long slowPluginThresholdMs) {
        final StringBuilder sb = new StringBuilder("[GoldLeaf Boot Waterfall]");
        for (final String phase : BOOT_PHASE_ORDER) {
            final Long nanos = this.phaseDurationNanos.get(phase);
            if (nanos != null) {
                sb.append(' ').append(phase).append('=').append(formatMs(nanos));
            }
        }
        final PluginIoPrefetcher.PrefetchStats prefetch = PluginIoPrefetcher.lastStats();
        if (prefetch != null && (prefetch.filesPrefetched() > 0 || prefetch.errorCount() > 0)) {
            sb.append(String.format(
                Locale.ROOT,
                " | io-prefetch=%d files (%.1f MiB in %.1f ms, skippedCap=%d, errors=%d)",
                prefetch.filesPrefetched(),
                prefetch.bytesPrefetched() / (1024.0 * 1024.0),
                prefetch.elapsedNanos() / 1_000_000.0,
                prefetch.filesSkippedCap(),
                prefetch.errorCount()
            ));
        }
        final List<PluginTiming> slow = this.slowPlugins(slowPluginThresholdMs);
        if (!slow.isEmpty()) {
            sb.append("\n  Slow plugins (>= ").append(Math.max(1L, slowPluginThresholdMs)).append(" ms): ");
            for (int i = 0; i < slow.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                final PluginTiming timing = slow.get(i);
                sb.append(timing.pluginName()).append('=').append(formatMs(timing.durationNanos()));
            }
        }
        return sb.toString();
    }

    public String formatShutdownSummary(final List<LingeringThread> lingeringThreads) {
        final StringBuilder sb = new StringBuilder("[GoldLeaf Shutdown Drain]");
        for (final String phase : SHUTDOWN_PHASE_ORDER) {
            final Long nanos = this.phaseDurationNanos.get(phase);
            if (nanos != null) {
                sb.append(' ').append(phase).append('=').append(formatMs(nanos));
            }
        }
        if (lingeringThreads != null && !lingeringThreads.isEmpty()) {
            sb.append("\n  Lingering non-daemon threads (").append(lingeringThreads.size()).append("):");
            for (final LingeringThread thread : lingeringThreads) {
                sb.append("\n    - ").append(thread.name()).append(" [").append(thread.state()).append(']');
                if (!thread.topFrames().isEmpty()) {
                    sb.append(" @ ").append(String.join(" <- ", thread.topFrames()));
                }
            }
        }
        return sb.toString();
    }

    public static List<LingeringThread> auditLingeringNonDaemonThreads() {
        return filterLingeringThreads(Thread.getAllStackTraces(), Thread.currentThread());
    }

    static List<LingeringThread> filterLingeringThreads(
        final Map<Thread, StackTraceElement[]> traces,
        final Thread currentThread
    ) {
        final List<LingeringThread> lingering = new ArrayList<>();
        for (final Map.Entry<Thread, StackTraceElement[]> entry : traces.entrySet()) {
            final Thread thread = entry.getKey();
            if (thread == null || thread == currentThread || thread.isDaemon() || !thread.isAlive()) {
                continue;
            }
            final String name = thread.getName();
            if (name == null || IGNORED_JVM_THREADS.contains(name) || isIgnoredRuntimeThread(name)) {
                continue;
            }
            final StackTraceElement[] stack = entry.getValue();
            final List<String> topFrames = new ArrayList<>(MAX_STACK_FRAMES);
            if (stack != null) {
                for (int i = 0; i < Math.min(MAX_STACK_FRAMES, stack.length); i++) {
                    topFrames.add(stack[i].toString());
                }
            }
            lingering.add(new LingeringThread(name, thread.getState(), topFrames));
        }
        lingering.sort(Comparator.comparing(LingeringThread::name));
        return List.copyOf(lingering);
    }

    public void logBootSummary() {
        final GoldLeafConfig.Diagnostics diag = GoldLeafConfig.get().diagnostics;
        if (!diag.bootTelemetryEnabled) {
            return;
        }
        this.markBootComplete();
        LOGGER.info("{}", this.formatBootSummary(diag.bootTelemetrySlowPluginMs));
    }

    public void logShutdownSummaryAndAudit() {
        final GoldLeafConfig.Diagnostics diag = GoldLeafConfig.get().diagnostics;
        if (!diag.bootTelemetryEnabled && !diag.shutdownNonDaemonThreadAudit) {
            return;
        }
        this.markShutdownComplete();
        final List<LingeringThread> lingering = diag.shutdownNonDaemonThreadAudit
            ? auditLingeringNonDaemonThreads()
            : List.of();
        final String summary = this.formatShutdownSummary(lingering);
        if (!lingering.isEmpty()) {
            LOGGER.warn("{}", summary);
        } else {
            LOGGER.info("{}", summary);
        }
    }

    private static boolean isIgnoredRuntimeThread(final String name) {
        for (final String prefix : IGNORED_RUNTIME_THREAD_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String formatMs(final long nanos) {
        return String.format(Locale.ROOT, "%.1fms", nanos / 1_000_000.0);
    }
}
