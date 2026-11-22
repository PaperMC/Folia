package dev.leafcraft.goldleaf.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurateException;

/**
 * Verification tests for {@code goldleaf-ADR-016} Layers B and C: monotonic boot/shutdown
 * waterfall telemetry, slow-plugin attribution, lingering non-daemon thread filtering, bounded
 * plugin JAR/YAML page-cache prefetch, and fail-closed config bounds.
 */
final class BootShutdownTelemetryAndPrefetchTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsBootWaterfallAndSortsSlowPluginsDescending() {
        final AtomicLong clock = new AtomicLong(1_000_000_000L);
        final BootShutdownTelemetry telemetry = new BootShutdownTelemetry(clock::get);

        telemetry.markBootStart();
        telemetry.startPhase(BootShutdownTelemetry.PHASE_BOOTSTRAP);
        clock.addAndGet(400_000_000L);
        assertEquals(400_000_000L, telemetry.endPhase(BootShutdownTelemetry.PHASE_BOOTSTRAP));

        telemetry.startPhase(BootShutdownTelemetry.PHASE_DATAPACKS);
        clock.addAndGet(550_000_000L);
        telemetry.endPhase(BootShutdownTelemetry.PHASE_DATAPACKS);

        telemetry.startPhase(BootShutdownTelemetry.PHASE_LEVEL_LOAD);
        clock.addAndGet(150_000_000L);
        telemetry.endPhase(BootShutdownTelemetry.PHASE_LEVEL_LOAD);

        telemetry.recordPluginEnable("FastPlugin", 50_000_000L);
        telemetry.recordPluginEnable("SlowPluginA", 320_000_000L);
        telemetry.recordPluginEnable("SlowPluginB", 910_000_000L);
        telemetry.markBootComplete();

        final List<BootShutdownTelemetry.PluginTiming> slow = telemetry.slowPlugins(250L);
        assertEquals(2, slow.size());
        assertEquals("SlowPluginB", slow.get(0).pluginName());
        assertEquals("SlowPluginA", slow.get(1).pluginName());

        final String summary = telemetry.formatBootSummary(250L);
        assertTrue(summary.contains("[GoldLeaf Boot Waterfall]"));
        assertTrue(summary.contains("bootstrap=400.0ms"));
        assertTrue(summary.contains("datapacks=550.0ms"));
        assertTrue(summary.contains("level-load=150.0ms"));
        assertTrue(summary.contains("total-boot=1100.0ms"));
        assertTrue(summary.contains("SlowPluginB=910.0ms, SlowPluginA=320.0ms"));
        assertFalse(summary.contains("FastPlugin"));
    }

    @Test
    void shutdownAuditFiltersDaemonCurrentAndJvmSystemThreads() throws Exception {
        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);

        final Thread lingeringNonDaemon = new Thread(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "LeakyPlugin-Worker-1");
        lingeringNonDaemon.setDaemon(false);

        final Thread harmlessDaemon = new Thread(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "Harmless-Daemon-1");
        harmlessDaemon.setDaemon(true);

        lingeringNonDaemon.start();
        harmlessDaemon.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));

            final Map<Thread, StackTraceElement[]> traces = new HashMap<>();
            traces.put(Thread.currentThread(), new StackTraceElement[0]);
            traces.put(harmlessDaemon, new StackTraceElement[0]);
            traces.put(lingeringNonDaemon, new StackTraceElement[]{
                new StackTraceElement("com.example.leaky.Pool", "park", "Pool.java", 88)
            });

            final List<BootShutdownTelemetry.LingeringThread> found =
                BootShutdownTelemetry.filterLingeringThreads(traces, Thread.currentThread());
            assertEquals(1, found.size());
            assertEquals("LeakyPlugin-Worker-1", found.get(0).name());
            assertEquals(1, found.get(0).topFrames().size());
            assertTrue(found.get(0).topFrames().get(0).contains("com.example.leaky.Pool.park"));

            final AtomicLong clock = new AtomicLong(10_000_000L);
            final BootShutdownTelemetry telemetry = new BootShutdownTelemetry(clock::get);
            telemetry.markShutdownStart();
            telemetry.startPhase(BootShutdownTelemetry.PHASE_SHUTDOWN_PLUGINS_DISABLE);
            clock.addAndGet(120_000_000L);
            telemetry.endPhase(BootShutdownTelemetry.PHASE_SHUTDOWN_PLUGINS_DISABLE);
            telemetry.markShutdownComplete();

            final String summary = telemetry.formatShutdownSummary(found);
            assertTrue(summary.contains("[GoldLeaf Shutdown Drain]"));
            assertTrue(summary.contains("plugins-disable=120.0ms"));
            assertTrue(summary.contains("Lingering non-daemon threads (1):"));
            assertTrue(summary.contains("LeakyPlugin-Worker-1"));
        } finally {
            release.countDown();
            lingeringNonDaemon.join(2_000L);
            harmlessDaemon.join(2_000L);
        }
    }

    @Test
    void pluginIoPrefetcherReadsJarsAndNestedYamlsAndHonorsCapsAndImmediateFdClose() throws Exception {
        final Path pluginsDir = this.tempDir.resolve("plugins");
        final Path pluginData = pluginsDir.resolve("ExamplePlugin");
        final Path deepNested = pluginData.resolve("nested");
        Files.createDirectories(deepNested);

        final byte[] jarBytes = new byte[4096];
        for (int i = 0; i < jarBytes.length; i++) {
            jarBytes[i] = (byte) (i & 0xFF);
        }
        final Path jarFile = pluginsDir.resolve("ExamplePlugin.jar");
        Files.write(jarFile, jarBytes);
        Files.writeString(pluginData.resolve("config.yml"), "enabled: true\n", StandardCharsets.UTF_8);
        Files.writeString(pluginData.resolve("ignored.txt"), "not a yaml\n", StandardCharsets.UTF_8);
        Files.writeString(deepNested.resolve("too-deep.yml"), "ignored: true\n", StandardCharsets.UTF_8);

        final List<Path> targets = PluginIoPrefetcher.discoverTargets(pluginsDir);
        assertEquals(2, targets.size());
        assertTrue(targets.contains(jarFile));
        assertTrue(targets.contains(pluginData.resolve("config.yml")));

        try (PluginIoPrefetcher.PrefetchHandle handle = PluginIoPrefetcher.startAsync(
            pluginsDir,
            2,
            1024L * 1024L,
            10L * 1024L * 1024L
        )) {
            final PluginIoPrefetcher.PrefetchStats stats = handle.drainAndStop(2_000L);
            assertEquals(2, stats.filesPrefetched());
            assertEquals(jarBytes.length + "enabled: true\n".getBytes(StandardCharsets.UTF_8).length, stats.bytesPrefetched());
            assertEquals(0, stats.errorCount());
            assertFalse(stats.cancelled());
        }

        // Verify file handles are closed after drain by replacing the jar cleanly
        Files.delete(jarFile);
        assertFalse(Files.exists(jarFile));
    }

    @Test
    void pluginIoPrefetcherEnforcesTotalByteCap() throws Exception {
        final Path pluginsDir = this.tempDir.resolve("plugins-capped");
        Files.createDirectories(pluginsDir);
        Files.write(pluginsDir.resolve("a.jar"), new byte[8192]);
        Files.write(pluginsDir.resolve("b.jar"), new byte[8192]);

        try (PluginIoPrefetcher.PrefetchHandle handle = PluginIoPrefetcher.startAsync(
            pluginsDir,
            1,
            2048L,
            2048L
        )) {
            final PluginIoPrefetcher.PrefetchStats stats = handle.drainAndStop(2_000L);
            assertTrue(stats.bytesPrefetched() <= 2048L);
            assertEquals(1, stats.filesPrefetched());
            assertEquals(1, stats.filesSkippedCap());
        }
    }

    @Test
    void goldLeafConfigRejectsInvalidBootTelemetryAndPrefetchBounds() throws Exception {
        final Path badSlowMs = this.tempDir.resolve("bad-slow-ms.yml");
        Files.writeString(
            badSlowMs,
            """
                diagnostics:
                  boot-telemetry-slow-plugin-ms: 0
                """,
            StandardCharsets.UTF_8
        );
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(badSlowMs));

        final Path badThreads = this.tempDir.resolve("bad-prefetch-threads.yml");
        Files.writeString(
            badThreads,
            """
                diagnostics:
                  plugin-io-prefetch-threads: 32
                """,
            StandardCharsets.UTF_8
        );
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(badThreads));

        final Path badTotal = this.tempDir.resolve("bad-prefetch-total.yml");
        Files.writeString(
            badTotal,
            """
                diagnostics:
                  plugin-io-prefetch-max-file-bytes: 4194304
                  plugin-io-prefetch-max-total-bytes: 2097152
                """,
            StandardCharsets.UTF_8
        );
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(badTotal));
    }
}
