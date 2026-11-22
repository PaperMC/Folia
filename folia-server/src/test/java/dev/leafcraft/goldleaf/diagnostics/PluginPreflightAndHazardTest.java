package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.configurate.ConfigurateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginPreflightAndHazardTest {

    @AfterEach
    void tearDown() {
        PluginPreflightDiscoveryHook.clearProviders();
        TickHazardMonitor.clearListeners();
        TickHazardMonitor.resetCounters();
        TickHazardMonitor.setActive(false);
    }

    @Test
    void preflightRunsOffThreadIsolatesCorruptArchiveAndAbortsWhenFailClosed(@TempDir final Path tempDir)
        throws IOException {
        final Path validJar = tempDir.resolve("valid-plugin.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(validJar))) {
            zos.putNextEntry(new ZipEntry("plugin.yml"));
            zos.write("name: ValidPlugin\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        final Path corruptJar = tempDir.resolve("corrupt-plugin.jar");
        Files.write(corruptJar, new byte[] { 0x50, 0x4B, 0x00, 0x01, 0x02 }); // truncated non-zip bytes

        final String callerThread = Thread.currentThread().getName();
        final AtomicReference<String> workerThread = new AtomicReference<>();
        final AtomicInteger validInspected = new AtomicInteger();

        PluginPreflightDiscoveryHook.registerProvider(archive -> {
            workerThread.set(Thread.currentThread().getName());
            if (archive.equals(validJar)) {
                validInspected.incrementAndGet();
            }
            return List.of();
        });

        // Non-fail-closed mode: records UNREADABLE_ARCHIVE for corruptJar AND still scans validJar
        final PluginPreflightDiscoveryHook.PreflightReport report = PluginPreflightDiscoveryHook.runPreflight(
            List.of(corruptJar, validJar),
            false
        );
        assertNotEquals(callerThread, workerThread.get(), "preflight must execute off the caller thread");
        assertEquals(2, report.scannedArchives().size());
        assertEquals(1, validInspected.get(), "valid archive must still be inspected after corrupt archive");
        assertTrue(report.hasCriticalFindings());
        assertTrue(report.findings().stream().anyMatch(f -> "UNREADABLE_ARCHIVE".equals(f.code())));

        // Fail-closed CI mode: aborts boot deterministically
        assertThrows(
            IllegalStateException.class,
            () -> PluginPreflightDiscoveryHook.runPreflight(List.of(corruptJar, validJar), true)
        );
    }

    @Test
    void hazardMonitorIdleByDefaultCountsAccuratelyAndQuarantinesFaultyListener() {
        assertFalse(TickHazardMonitor.isActive());

        // When inactive, nothing is counted
        TickHazardMonitor.recordSyncChunkLoad("world", 1, 2, "ExampleCaller");
        TickHazardMonitor.recordTickIfStalled(1L, TimeUnit.MILLISECONDS.toNanos(250), "region_tick");
        TickHazardMonitor.recordWrongRegionAccess("wrong region", "ctx");
        assertEquals(0L, TickHazardMonitor.syncChunkLoadCount());
        assertEquals(0L, TickHazardMonitor.tickStallCount());
        assertEquals(0L, TickHazardMonitor.wrongRegionAccessCount());

        // Enable hooks and attach one faulty + one healthy listener
        TickHazardMonitor.setActive(true);
        final AtomicInteger faultyCalls = new AtomicInteger();
        final AtomicInteger healthySync = new AtomicInteger();
        final AtomicInteger healthyStall = new AtomicInteger();
        final AtomicInteger healthyWrong = new AtomicInteger();

        TickHazardMonitor.registerListener(new TickHazardListener() {
            @Override
            public void onSyncChunkLoad(final String worldName, final int chunkX, final int chunkZ, final String callerClass) {
                faultyCalls.incrementAndGet();
                throw new RuntimeException("simulated listener crash");
            }
        });
        TickHazardMonitor.registerListener(new TickHazardListener() {
            @Override
            public void onSyncChunkLoad(final String worldName, final int chunkX, final int chunkZ, final String callerClass) {
                healthySync.incrementAndGet();
            }

            @Override
            public void onTickStall(final long regionId, final long durationNanos, final String tickPhase) {
                healthyStall.incrementAndGet();
            }

            @Override
            public void onWrongRegionAccess(final String reason, final String context) {
                healthyWrong.incrementAndGet();
            }
        });

        TickHazardMonitor.recordSyncChunkLoad("world", 0, 0, "PluginA");
        TickHazardMonitor.recordSyncChunkLoad("world", 1, 1, "PluginB");
        TickHazardMonitor.recordTickIfStalled(7L, TimeUnit.MILLISECONDS.toNanos(50), "region_tick");  // below 100 ms
        TickHazardMonitor.recordTickIfStalled(7L, TimeUnit.MILLISECONDS.toNanos(150), "region_tick"); // above 100 ms
        TickHazardMonitor.recordWrongRegionAccess("cross-region write", "region=7");

        assertEquals(1, faultyCalls.get(), "faulty listener must be quarantined after first throw");
        assertEquals(2L, TickHazardMonitor.syncChunkLoadCount());
        assertEquals(1L, TickHazardMonitor.tickStallCount());
        assertEquals(1L, TickHazardMonitor.wrongRegionAccessCount());
        assertEquals(2, healthySync.get());
        assertEquals(1, healthyStall.get());
        assertEquals(1, healthyWrong.get());
    }

    @Test
    void goldLeafConfigValidatesDiagnosticsBoundsFailClosed() {
        final GoldLeafConfig badListeners = GoldLeafConfig.defaults();
        badListeners.diagnostics.chunkTelemetryMaxListeners = 0;
        assertThrows(ConfigurateException.class, badListeners::validate);

        final GoldLeafConfig badThreshold = GoldLeafConfig.defaults();
        badThreshold.diagnostics.runtimeHazardStallThresholdMs = 19;
        assertThrows(ConfigurateException.class, badThreshold::validate);
    }

    @Test
    void preflightNeverLoadsScannedPluginClasses(@TempDir final Path tempDir) throws IOException {
        final String sentinelProp = "goldleaf.test.clinit.executed";
        System.clearProperty(sentinelProp);
        final Path pluginJar = tempDir.resolve("sentinel-plugin.jar");
        final byte[] sentinelClassBytes = buildSentinelClinitClass(
            "com/example/sentinel/NeverLoadMePlugin",
            sentinelProp
        );
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(pluginJar))) {
            zos.putNextEntry(new ZipEntry("plugin.yml"));
            zos.write("name: SentinelPlugin\nmain: com.example.sentinel.NeverLoadMePlugin\nfolia-supported: true\n"
                .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("com/example/sentinel/NeverLoadMePlugin.class"));
            zos.write(sentinelClassBytes);
            zos.closeEntry();
        }

        PluginPreflightDiscoveryHook.registerProvider(BytecodeFoliaPreflightProvider.instance());
        final PluginPreflightDiscoveryHook.PreflightReport report = PluginPreflightDiscoveryHook.runPreflight(
            List.of(pluginJar),
            false
        );

        assertEquals(1, report.scannedArchives().size());
        assertNull(
            System.getProperty(sentinelProp),
            "preflight MUST NOT load plugin classes or trigger <clinit>"
        );
        assertThrows(
            ClassNotFoundException.class,
            () -> Class.forName("com.example.sentinel.NeverLoadMePlugin", false, getClass().getClassLoader()),
            "scanned plugin class must never be defined on any active classloader"
        );
    }

    @Test
    void bytecodeApiPreflightDetectsUnmanagedThreadAndWritesReports(@TempDir final Path tempDir) throws IOException {
        final Path pluginJar = tempDir.resolve("thread-spawner.jar");
        final byte[] listenerBytes = buildUnmanagedThreadListenerClass("com/example/spawner/RawThreadListener");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(pluginJar))) {
            zos.putNextEntry(new ZipEntry("plugin.yml"));
            zos.write("name: ThreadSpawner\nmain: com.example.spawner.RawThreadListener\n"
                .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("com/example/spawner/RawThreadListener.class"));
            zos.write(listenerBytes);
            zos.closeEntry();
        }

        final Path reportDir = tempDir.resolve("preflight-reports");
        final Path previousDir = PluginPreflightDiscoveryHook.reportOutputDirectory();
        PluginPreflightDiscoveryHook.setReportOutputDirectory(reportDir);
        try {
            PluginPreflightDiscoveryHook.registerProvider(BytecodeFoliaPreflightProvider.instance());
            final PluginPreflightDiscoveryHook.PreflightReport report = PluginPreflightDiscoveryHook.runPreflight(
                List.of(pluginJar),
                false,
                true
            );

            assertEquals(1, report.scannedArchives().size());
            assertEquals(1, report.warnCount());
            assertTrue(
                report.findings().stream().anyMatch(f -> BytecodeFoliaPreflightProvider.CODE_FOLIA_UNDECLARED.equals(f.code())),
                "missing folia-supported must be flagged"
            );
            assertTrue(
                report.findings().stream().anyMatch(f -> BytecodeFoliaPreflightProvider.CODE_UNMANAGED_THREAD_SPAWN.equals(f.code())),
                ":bytecode-api must detect unmanaged raw Thread spawn"
            );
            final String summary = PluginPreflightDiscoveryHook.formatSummaryTable(report, false);
            assertTrue(summary.contains("WARN=1"), "summary table must report WARN=1");
            assertTrue(summary.contains("thread-spawner.jar"), "summary table must list archive name");

            final Path jsonReport = reportDir.resolve("latest-preflight.json");
            final Path mdReport = reportDir.resolve("latest-preflight.md");
            assertTrue(Files.isRegularFile(jsonReport), "JSON preflight report must be written");
            assertTrue(Files.isRegularFile(mdReport), "Markdown preflight report must be written");
            assertTrue(Files.readString(jsonReport, StandardCharsets.UTF_8).contains("UNMANAGED_THREAD_SPAWN"));
            assertTrue(Files.readString(mdReport, StandardCharsets.UTF_8).contains("thread-spawner.jar"));
        } finally {
            PluginPreflightDiscoveryHook.setReportOutputDirectory(previousDir);
        }
    }

    @Test
    void hazardMonitorResolvesCallerWhenListenersActiveAndStaysFastWhenIdle() {
        TickHazardMonitor.setActive(false);
        final int iterations = 200_000;
        final long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            TickHazardMonitor.recordSyncChunkLoad("world", i, i, null);
            TickHazardMonitor.recordTickIfStalled(1L, 500_000_000L, "region_tick");
            TickHazardMonitor.recordWrongRegionAccess("wrong", "ctx");
        }
        final long elapsedNanos = System.nanoTime() - startNanos;
        assertEquals(0L, TickHazardMonitor.syncChunkLoadCount());
        assertEquals(0L, TickHazardMonitor.tickStallCount());
        assertEquals(0L, TickHazardMonitor.wrongRegionAccessCount());
        // Idle path must remain sub-microsecond even on slow/contended CI runners
        assertTrue(
            elapsedNanos < TimeUnit.MILLISECONDS.toNanos(500),
            "idle hazard monitor fast path took too long: " + elapsedNanos + " ns"
        );

        TickHazardMonitor.setActive(true);
        final AtomicReference<String> capturedCaller = new AtomicReference<>();
        TickHazardMonitor.registerListener(new TickHazardListener() {
            @Override
            public void onSyncChunkLoad(final String worldName, final int chunkX, final int chunkZ, final String callerClass) {
                capturedCaller.set(callerClass);
            }
        });
        assertTrue(TickHazardMonitor.hasActiveListeners());
        TickHazardMonitor.recordSyncChunkLoad("world", 3, 4, null);
        assertNotNull(capturedCaller.get(), "callerClass must be resolved via StackWalker when listeners are active");
        assertTrue(
            capturedCaller.get().contains("PluginPreflightAndHazardTest"),
            "resolved caller should attribute to the invoking class, got: " + capturedCaller.get()
        );
    }

    private static byte[] buildSentinelClinitClass(final String internalName, final String sentinelProp) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        final MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn(sentinelProp);
        clinit.visitLdcInsn("EXECUTED");
        clinit.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "setProperty",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            false
        );
        clinit.visitInsn(Opcodes.POP);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(2, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildUnmanagedThreadListenerClass(final String internalName) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC,
            internalName,
            null,
            "java/lang/Object",
            new String[] { "org/bukkit/command/CommandExecutor" }
        );
        final MethodVisitor mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "onCommand",
            "(Lorg/bukkit/command/CommandSender;Lorg/bukkit/command/Command;Ljava/lang/String;[Ljava/lang/String;)Z",
            null,
            null
        );
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/Thread");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "start", "()V", false);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(2, 5);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
