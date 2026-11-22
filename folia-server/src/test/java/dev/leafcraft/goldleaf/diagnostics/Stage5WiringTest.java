package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.scheduler.GoldLeafPredictPacer;
import dev.leafcraft.goldleaf.telemetry.GoldLeafMetricsCollector;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Stage5WiringTest {

    @Test
    void eventLoopGroupHolderResolvesNativeOrNioTransportCleanly() {
        final EventLoopGroupHolder holder = EventLoopGroupHolder.remote(null, true);
        assertNotNull(holder);
        assertNotNull(holder.channelCls());
        assertNotNull(holder.serverChannelCls());
    }

    @Test
    void bytecodePreflightDetectsUndeclaredFoliaAndLegacyBukkitScheduler(@TempDir final Path tempDir) throws IOException {
        final Path pluginJar = tempDir.resolve("legacy-plugin.jar");
        final ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/LegacyPlugin", null, "java/lang/Object", null);
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onEnable", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/bukkit/Bukkit", "getScheduler", "()Lorg/bukkit/scheduler/BukkitScheduler;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(pluginJar))) {
            zos.putNextEntry(new ZipEntry("plugin.yml"));
            zos.write("name: LegacyPlugin\nversion: 1.0\nmain: com.example.LegacyPlugin\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("com/example/LegacyPlugin.class"));
            zos.write(cw.toByteArray());
            zos.closeEntry();
        }

        final List<PluginPreflightDiscoveryHook.PreflightFinding> findings =
            BytecodeFoliaPreflightProvider.instance().inspectArchive(pluginJar);
        assertTrue(findings.stream().anyMatch(f -> BytecodeFoliaPreflightProvider.CODE_FOLIA_UNDECLARED.equals(f.code())));
        assertTrue(findings.stream().anyMatch(f -> BytecodeFoliaPreflightProvider.CODE_BUKKIT_SCHEDULER_CALL.equals(f.code())));
    }

    @Test
    void predictPacerCutsBudgetOnMsptBreachAndDebitsTokens() {
        final GoldLeafPredictPacer pacer = new GoldLeafPredictPacer();
        final long t0 = TimeUnit.SECONDS.toNanos(10);
        final double initial = pacer.currentPublishedMs(GoldLeafPredictPacer.WorkLane.CHUNK_GEN_DISPATCH);
        final long budgetAfterBreach = pacer.evaluateLaneBudgetNanos(
            GoldLeafPredictPacer.WorkLane.CHUNK_GEN_DISPATCH,
            55.0,
            40.0,
            TimeUnit.MILLISECONDS.toNanos(50),
            false,
            t0
        );
        assertTrue(pacer.currentPublishedMs(GoldLeafPredictPacer.WorkLane.CHUNK_GEN_DISPATCH) < initial);
        assertTrue(budgetAfterBreach > 0L);
        assertTrue(pacer.chargeWork(GoldLeafPredictPacer.WorkLane.CHUNK_GEN_DISPATCH, budgetAfterBreach / 2, t0));
        assertFalse(pacer.chargeWork(GoldLeafPredictPacer.WorkLane.CHUNK_GEN_DISPATCH, budgetAfterBreach * 2, t0));
    }

    @Test
    void metricsCollectorAggregatesFoliaRegionSamplesIntoMetricsSnapshot() {
        final GoldLeafMetricsCollector collector = new GoldLeafMetricsCollector();
        collector.recordRegionSample("world@0,0", 20.0, 12.5, 4, 1, 100L, 5000L);
        collector.recordRegionSample("world@32,0", 18.2, 41.0, 7, 3, 100L, 5000L);

        final MetricsSnapshot snap = collector.snapshot(100);
        assertEquals(2, snap.foliaRegions.size());
        assertEquals(11, snap.playerCount);
        assertEquals(18.2, snap.tps1m, 1e-6);
        assertEquals(41.0, snap.mspt, 1e-6);
    }
}
