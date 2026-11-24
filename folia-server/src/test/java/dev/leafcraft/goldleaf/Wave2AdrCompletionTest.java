package dev.leafcraft.goldleaf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.spottedleaf.moonrise.spi.ChunkLoadBackpressureSpi;
import dev.leafcraft.goldleaf.bot.FoliaSyntheticPlayerBridge;
import dev.leafcraft.goldleaf.bot.SyntheticPlayerHandle;
import dev.leafcraft.goldleaf.bot.SyntheticPlayerService;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.diagnostics.GoldLeafCommands;
import dev.leafcraft.goldleaf.diagnostics.OfflinePluginAuditCli;
import dev.leafcraft.goldleaf.diagnostics.RegionHealthSnapshot;
import dev.leafcraft.goldleaf.network.BroadcastPacketEncoder;
import dev.leafcraft.goldleaf.network.LcaAntiXrayFastPath;
import dev.leafcraft.goldleaf.network.OffTickChunkPacketController;
import dev.leafcraft.goldleaf.security.AdaptiveChunkGenBackpressure;
import dev.leafcraft.goldleaf.security.DecodeTimePacketGuard;
import io.papermc.paper.spi.PaperNetworkSpi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies Wave 1 and Wave 2 GoldLeaf ADR-009..018 runtime wiring, CLI entry points,
 * S-002 quit eviction, Engine Mode 2 Anti-Xray, and opt-in chat-signing disable.
 */
final class Wave2AdrCompletionTest {

    @AfterEach
    void cleanup() {
        RegionHealthSnapshot.global().clearSubscribers();
        AdaptiveChunkGenBackpressure.DEFAULT.clear();
        DecodeTimePacketGuard.global().clear();
        SyntheticPlayerService.global().shutdownAll();
        GoldLeafConfig.get().security.disableChatSigning = false;
        GoldLeafConfig.get().bots.enabled = false;
    }

    @Test
    void bootstrapRegistersEncodersAndUnifiedPlayerQuitEviction() {
        GoldLeafBootstrap.registerAllLayer2Providers(GoldLeafConfig.get());
        assertNotNull(BroadcastPacketEncoder.get(), "SharedBroadcastPacketEncoder must be registered");
        assertNotNull(OffTickChunkPacketController.get(), "DefaultOffTickChunkPacketController must be registered");

        final UUID playerId = UUID.randomUUID();
        RegionHealthSnapshot.global().toggleHud(playerId);
        AdaptiveChunkGenBackpressure.DEFAULT.recordNewChunkGenerations(playerId, 64, 1_000_000L);
        DecodeTimePacketGuard.global().recordMalformed(playerId, "TestPacket", "bad", 10L);

        assertTrue(RegionHealthSnapshot.global().isHudSubscribed(playerId));
        assertTrue(AdaptiveChunkGenBackpressure.DEFAULT.trackedPlayers() >= 1);
        assertTrue(DecodeTimePacketGuard.global().trackedConnections() >= 1);

        PaperNetworkSpi.onPlayerQuit(playerId);

        assertFalse(RegionHealthSnapshot.global().isHudSubscribed(playerId));
        assertEquals(0, AdaptiveChunkGenBackpressure.DEFAULT.trackedPlayers());
        assertEquals(0, DecodeTimePacketGuard.global().trackedConnections());
    }

    @Test
    void backpressurePacesGenerationRateWithoutShrinkingSendDistance() {
        GoldLeafBootstrap.registerAllLayer2Providers(GoldLeafConfig.get());
        final UUID playerId = UUID.randomUUID();
        final long now = 10_000_000_000L;
        AdaptiveChunkGenBackpressure.DEFAULT.recordNewChunkGenerations(playerId, 200, now);

        final long pacedMaxGens = ChunkLoadBackpressureSpi.evaluateOrDefault(
            playerId,
            512,
            16L,
            12,
            now
        );
        final int pacedSendDistance = ChunkLoadBackpressureSpi.evaluateSendDistanceOrDefault(
            playerId,
            512,
            16L,
            12,
            now
        );
        assertTrue(pacedMaxGens >= 1L && pacedMaxGens < 16L, "Saturated high-debt player should have generation rate paced down");
        assertEquals(
            12,
            pacedSendDistance,
            "Backpressure must preserve client send/view distance without sending radius updates"
        );
    }

    @Test
    void engineMode2ObfuscatesInteriorOpaqueBlocksAndSkipsEmptySubCubes() {
        final int air = 0;
        final int stone = 1;
        final int fakeDiamond = 2;
        final int fakeGold = 3;
        final int[] src = new int[LcaAntiXrayFastPath.SECTION_BLOCKS];
        final int[] out = new int[LcaAntiXrayFastPath.SECTION_BLOCKS];

        // Fill a 3x3x3 cube of stone around (5,5,5) inside sub-cube (1,1,1)
        for (int y = 4; y <= 6; y++) {
            for (int z = 4; z <= 6; z++) {
                for (int x = 4; x <= 6; x++) {
                    src[(y << 8) | (z << 4) | x] = stone;
                }
            }
        }
        final boolean[] opaque = new boolean[] {false, true, true, true};
        final long mask = LcaAntiXrayFastPath.computeOctreeOccupancyMask64(src, air);
        final LcaAntiXrayFastPath.ObfuscationStats stats = LcaAntiXrayFastPath.obfuscateEngineMode2(
            src,
            out,
            mask,
            opaque,
            new int[] {fakeDiamond, fakeGold},
            false
        );

        assertEquals(1, stats.subCubesEvaluated());
        assertEquals(63, stats.subCubesSkipped());
        assertEquals(1, stats.blocksObfuscated());
        final int centerIdx = (5 << 8) | (5 << 4) | 5;
        assertTrue(out[centerIdx] == fakeDiamond || out[centerIdx] == fakeGold);
        // Exposed face block (4,5,5) must remain stone
        assertEquals(stone, out[(5 << 8) | (5 << 4) | 4]);
    }

    @Test
    void offlinePluginAuditCliScansSafeAndCorruptJarsAndWritesReports(@TempDir final Path tempDir) throws IOException {
        final Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);

        final Path safeJar = pluginsDir.resolve("safe-plugin.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(safeJar))) {
            zos.putNextEntry(new ZipEntry("paper-plugin.yml"));
            zos.write("name: SafePlugin\nfolia-supported: true\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        final Path jsonOut = tempDir.resolve("audit.json");
        final Path mdOut = tempDir.resolve("audit.md");
        final OfflinePluginAuditCli.AuditResult okResult = OfflinePluginAuditCli.run(
            OfflinePluginAuditCli.parseArgs(new String[] {
                pluginsDir.toString(),
                "--json", jsonOut.toString(),
                "--markdown", mdOut.toString()
            })
        );
        assertEquals(0, okResult.exitCode());
        assertEquals(1, okResult.report().safeCount());
        assertTrue(Files.isRegularFile(jsonOut));
        assertTrue(Files.isRegularFile(mdOut));

        // Add corrupt jar -> must fail closed with non-zero exit code
        Files.writeString(pluginsDir.resolve("corrupt.jar"), "not-a-zip", StandardCharsets.UTF_8);
        final OfflinePluginAuditCli.AuditResult failResult = OfflinePluginAuditCli.run(
            new OfflinePluginAuditCli.Options(pluginsDir, null, null, false)
        );
        assertEquals(1, failResult.exitCode());
        assertTrue(failResult.report().hasCriticalFindings());

        // Missing target directory must fail closed with exit code 2 (ADR-018, S-004)
        final Path missingDir = tempDir.resolve("does-not-exist");
        final OfflinePluginAuditCli.Options parsedStrict = OfflinePluginAuditCli.parseArgs(new String[] {
            missingDir.toString(),
            "--format", "json",
            "--strict"
        });
        assertTrue(parsedStrict.failOnWarn());
        assertEquals(OfflinePluginAuditCli.OutputFormat.JSON, parsedStrict.format());
        final OfflinePluginAuditCli.AuditResult missingResult = OfflinePluginAuditCli.run(parsedStrict);
        assertEquals(2, missingResult.exitCode());
        assertTrue(missingResult.report().hasCriticalFindings());
    }

    @Test
    void chatSigningDisableDefaultsFalseAndReflectsConfig() {
        assertFalse(GoldLeafConfig.defaults().security.disableChatSigning, "Chat signing disable must default off (ADR-018)");
        GoldLeafBootstrap.registerAllLayer2Providers(GoldLeafConfig.get());
        assertFalse(PaperNetworkSpi.isChatSigningDisabled());
        GoldLeafConfig.get().security.disableChatSigning = true;
        assertTrue(PaperNetworkSpi.isChatSigningDisabled());
        GoldLeafConfig.get().security.disableChatSigning = false;
        assertFalse(PaperNetworkSpi.isChatSigningDisabled(), "Disabling toggle must restore signed chat path");
    }

    @Test
    void syntheticPlayerBridgeAndCommandsWireCleanly() {
        final FoliaSyntheticPlayerBridge bridge = new FoliaSyntheticPlayerBridge();
        final SyntheticPlayerService service = SyntheticPlayerService.global();
        service.setRegionDispatcher(bridge);
        service.setLifecycleBridge(bridge);
        GoldLeafConfig.get().bots.enabled = true;

        final UUID owner = UUID.randomUUID();
        final SyntheticPlayerHandle handle = service.spawn(
            "farm_bot",
            owner,
            new SyntheticPlayerHandle.PositionSnapshot("world", 0, 64, 0, 0f, 0f),
            false
        ).join();
        assertEquals(1, bridge.attachedCount());
        assertTrue(service.despawn(handle.uuid()));
        assertEquals(0, bridge.attachedCount());

        assertNotNull(GoldLeafCommands.createGoldLeafCommand());
        assertNotNull(GoldLeafCommands.createBotCommand());
    }

    @Test
    void packedOpenClBaselineKernelsLoadFromJarResources(@TempDir final Path tempDir) {
        try (dev.leafcraft.goldleaf.worldgen.OpenClKernelCache cache =
                 new dev.leafcraft.goldleaf.worldgen.OpenClKernelCache(tempDir)) {
            final var fp64 = cache.loadPackedSource("vanilla_overworld_baseline_fp64");
            final var fp32 = cache.loadPackedSource("vanilla_overworld_baseline_fp32");
            assertTrue(fp64.isPresent(), "Packed FP64 baseline kernel must be present in resources");
            assertTrue(fp32.isPresent(), "Packed FP32 baseline kernel must be present in resources");
            assertTrue(fp64.get().sourceCode().contains("goldleaf_density_volume_batch_fp64"));
            assertTrue(fp32.get().sourceCode().contains("goldleaf_density_volume_batch_fp32"));
            assertEquals(2L, cache.packedSourceHits());
        }
    }
}
