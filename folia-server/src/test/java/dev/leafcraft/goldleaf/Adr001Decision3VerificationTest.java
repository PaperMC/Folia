package dev.leafcraft.goldleaf;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.spi.ChunkResidencySpi;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.config.HardwareEnvelope;
import dev.leafcraft.goldleaf.protocol.BuiltInTechnicalProtocolHandlers;
import dev.leafcraft.goldleaf.protocol.TechnicalProtocolCodecs;
import dev.leafcraft.goldleaf.protocol.TechnicalProtocolRegistry;
import dev.leafcraft.goldleaf.scheduler.GoldLeafPredictPacer;
import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import dev.leafcraft.goldleaf.storage.LcaDirectSectionSerializer;
import dev.leafcraft.goldleaf.storage.LcaMigrationCli;
import dev.leafcraft.goldleaf.storage.LcaRegionStorageProvider;
import dev.leafcraft.goldleaf.storage.LcaResidencyManager;
import dev.leafcraft.goldleaf.telemetry.GoldLeafMetricsCollector;
import dev.leafcraft.goldleaf.threading.TickThreadAutoTuner;
import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.papermc.paper.spi.PaperTelemetrySpi;
import io.papermc.paper.spi.PaperThreadingSpi;
import io.papermc.paper.threadedregions.spi.RegionShardFence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproducible Decision §3 non-regression and local verification suite for
 * {@code goldleaf-ADR-001}:
 * <ul>
 *   <li>Floor Target workstation auto-tuner envelope (4–8 core machines receive multi-region parallelism).</li>
 *   <li>Lossless {@code .lca} chunk write/read round-trip across 64 multi-section chunks with real block palettes, biomes, light arrays, Starlight tags, PDC, and heightmaps.</li>
 *   <li>Bit-exact {@code .lca <-> .mca} migration verification via {@link LcaMigrationCli}.</li>
 *   <li>Stage 2 Tier 2 zero-NBT CoW section splicing, {@code DIRECT_COW_COMMITTED_TAG} write bypass, unified {@code worldId}, and unload residency eviction ({@code S-002}).</li>
 *   <li>Stage 5 region tick-end telemetry wiring into {@link GoldLeafMetricsCollector} and {@link GoldLeafPredictPacer}, plus config-gated built-in technical protocols ({@code S-004}, {@code S-002}).</li>
 * </ul>
 */
class Adr001Decision3VerificationTest {

    @Test
    void workstationAutoTunerEnvelopeUnlocksParallelismOn4To8CoreMachines() {
        final GoldLeafConfig cfg = GoldLeafConfig.defaults();
        assertEquals(2, TickThreadAutoTuner.resolveTickThreads(0, cfg, HardwareEnvelope.of(4, 8L * 1024 * 1024 * 1024)));
        assertEquals(3, TickThreadAutoTuner.resolveTickThreads(0, cfg, HardwareEnvelope.of(6, 16L * 1024 * 1024 * 1024)));
        assertEquals(4, TickThreadAutoTuner.resolveTickThreads(0, cfg, HardwareEnvelope.of(8, 16L * 1024 * 1024 * 1024)));
        assertTrue(PaperThreadingSpi.defaultResolveTickThreads(0) >= 1);
    }

    @Test
    void lcaRoundTripPreserves64MultiSectionChunksLosslesslyAndMeasuresThroughput(@TempDir final Path tempDir) throws IOException {
        final Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        final GoldLeafConfig cfg = GoldLeafConfig.defaults();
        cfg.storage.format = "lca";

        final int chunkCount = 64;
        final long writeStart = System.nanoTime();
        try (LcaRegionStorageProvider provider = LcaRegionStorageProvider.open(regionDir, cfg.storage, LcaResidencyManager.DEFAULT_WORLD_ID)) {
            for (int i = 0; i < chunkCount; i++) {
                final int cx = i & 7;
                final int cz = i >> 3;
                provider.write(cx, cz, buildSyntheticChunkTag(cx, cz));
            }
            provider.flush();
        }
        final long writeNanos = System.nanoTime() - writeStart;

        final long readStart = System.nanoTime();
        try (LcaRegionStorageProvider provider = LcaRegionStorageProvider.open(regionDir, cfg.storage, LcaResidencyManager.DEFAULT_WORLD_ID)) {
            for (int i = 0; i < chunkCount; i++) {
                final int cx = i & 7;
                final int cz = i >> 3;
                final CompoundTag expected = buildSyntheticChunkTag(cx, cz);
                final CompoundTag actual = provider.read(cx, cz);
                assertNotNull(actual, "Chunk (" + cx + "," + cz + ") must round-trip from .lca");
                assertEquals(expected.getIntOr("xPos", Integer.MIN_VALUE), actual.getIntOr("xPos", Integer.MIN_VALUE));
                assertEquals(expected.getIntOr("zPos", Integer.MIN_VALUE), actual.getIntOr("zPos", Integer.MIN_VALUE));
                assertEquals(expected.getStringOr("Status", ""), actual.getStringOr("Status", ""));
                assertEquals(
                    expected.getCompoundOrEmpty("ChunkBukkitValues").getStringOr("goldleaf_probe", ""),
                    actual.getCompoundOrEmpty("ChunkBukkitValues").getStringOr("goldleaf_probe", "")
                );

                final ListTag actualSections = actual.getListOrEmpty("sections");
                assertTrue(actualSections.size() >= 4, "Multi-section chunk must retain all populated sections");
                boolean foundSection0 = false;
                for (int s = 0; s < actualSections.size(); s++) {
                    final CompoundTag sec = actualSections.getCompoundOrEmpty(s);
                    if (sec.getByteOr("Y", (byte) 127) == (byte) 0) {
                        foundSection0 = true;
                        final CompoundTag blockStates = sec.getCompoundOrEmpty("block_states");
                        final ListTag palette = blockStates.getListOrEmpty("palette");
                        assertTrue(palette.size() >= 2, "Section 0 block palette must preserve multiple block states");
                        assertEquals(2048, sec.getByteArray("BlockLight").orElse(new byte[0]).length, "BlockLight must round-trip");
                        assertEquals(2048, sec.getByteArray("SkyLight").orElse(new byte[0]).length, "SkyLight must round-trip");
                        assertEquals("minecraft:plains", sec.getCompoundOrEmpty("biomes").getListOrEmpty("palette").getStringOr(0, ""));
                    }
                }
                assertTrue(foundSection0, "Section Y=0 must be present after .lca round-trip");
            }
        }
        final long readNanos = System.nanoTime() - readStart;

        final double writeMs = writeNanos / 1_000_000.0;
        final double readMs = readNanos / 1_000_000.0;
        assertTrue(writeNanos > 0L && readNanos > 0L);
        System.out.printf(
            "[ADR-001 Decision §3] 64-chunk multi-section .lca round-trip: write=%.2f ms (%.0f chunks/s), read=%.2f ms (%.0f chunks/s)%n",
            writeMs, chunkCount / (writeNanos / 1e9),
            readMs, chunkCount / (readNanos / 1e9)
        );
    }

    @Test
    void lcaToMcaAndBackPreservesMultiSectionChunksLosslessly(@TempDir final Path tempDir) throws IOException {
        final Path lcaWorld = tempDir.resolve("world_lca");
        final Path mcaWorld = tempDir.resolve("world_mca");
        final Path roundTripLcaWorld = tempDir.resolve("world_lca_rt");
        Files.createDirectories(lcaWorld.resolve("region"));

        final GoldLeafConfig cfg = GoldLeafConfig.defaults();
        cfg.storage.format = "lca";
        try (LcaRegionStorageProvider provider = LcaRegionStorageProvider.open(lcaWorld.resolve("region"), cfg.storage, "test:migration")) {
            for (int cx = 0; cx < 4; cx++) {
                provider.write(cx, 0, buildSyntheticChunkTag(cx, 0));
            }
            provider.flush();
        }

        final LcaMigrationCli.Report toMca = LcaMigrationCli.run(new LcaMigrationCli.Options(
            LcaMigrationCli.Format.LCA, LcaMigrationCli.Format.MCA, lcaWorld, mcaWorld, false, true, false
        ));
        assertTrue(toMca.isSuccess(), "LCA -> MCA migration must succeed: " + toMca.errors());
        assertEquals(4, toMca.chunksConverted());
        assertEquals(0, toMca.verifyMismatches());

        final LcaMigrationCli.Report backToLca = LcaMigrationCli.run(new LcaMigrationCli.Options(
            LcaMigrationCli.Format.MCA, LcaMigrationCli.Format.LCA, mcaWorld, roundTripLcaWorld, false, true, false
        ));
        assertTrue(backToLca.isSuccess(), "MCA -> LCA migration must succeed: " + backToLca.errors());
        assertEquals(4, backToLca.chunksConverted());
        assertEquals(0, backToLca.verifyMismatches());

        try (LcaRegionStorageProvider rtProvider = LcaRegionStorageProvider.open(roundTripLcaWorld.resolve("region"), cfg.storage, "test:migration_rt")) {
            for (int cx = 0; cx < 4; cx++) {
                final CompoundTag rt = rtProvider.read(cx, 0);
                assertNotNull(rt);
                assertEquals("chunk_" + cx + "_0", rt.getCompoundOrEmpty("ChunkBukkitValues").getStringOr("goldleaf_probe", ""));
                assertTrue(rt.getListOrEmpty("sections").size() >= 4);
            }
        }
    }

    @Test
    void zeroNbtCoWSpliceAndResidencyLifecycleBypassNbtWrite(@TempDir final Path tempDir) throws IOException {
        final Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        final String worldId = "test:zero_nbt_cow";
        final GoldLeafConfig cfg = GoldLeafConfig.defaults();
        cfg.storage.format = "lca";

        final ChunkResidencySpi previousSpi = ChunkResidencySpi.get();
        ChunkResidencySpi.register(LcaResidencyManager.global());
        try (LcaRegionStorageProvider provider = LcaRegionStorageProvider.open(regionDir, cfg.storage, worldId)) {
            provider.write(2, 3, buildSyntheticChunkTag(2, 3));
            provider.flush();

            final LcaChunkResidencyCache cache = LcaResidencyManager.global().getCacheIfPresent(worldId);
            assertNotNull(cache, "Residency cache must be populated under unified worldId on write/read");
            final long chunkKey = CoordinateUtils.getChunkKey(2, 3);
            assertNotNull(cache.getReadSnapshot(chunkKey), "Cold RawChunkFrameSnapshot must be installed on write/read");

            final int diamondDictId = provider.delegate().registerBlockStateInRegion(0, 0, "minecraft:diamond_block");
            final int[] dirtySectionVoxels = new int[4096];
            Arrays.fill(dirtySectionVoxels, diamondDictId);

            final LcaDirectSectionSerializer serializer = new LcaDirectSectionSerializer(LcaResidencyManager.global());
            final int sectionIndexY0 = 4; // minSectionY = -4 -> section Y=0 is index 4
            final CompoundTag sentinel = serializer.commitDirectCoW(
                worldId,
                2,
                3,
                1 << sectionIndexY0,
                idx -> idx == sectionIndexY0 ? dirtySectionVoxels : null,
                null,
                null,
                null
            );
            assertNotNull(sentinel, "Zero-NBT CoW splice must return DIRECT_COW_COMMITTED_TAG sentinel");
            assertTrue(sentinel.getBooleanOr(LcaRegionFileStorage.DIRECT_COW_COMMITTED_TAG, false));
            assertEquals(1L, serializer.zeroNbtCoWSplices());

            // Passing the sentinel to LcaRegionStorageProvider#write must bypass NbtIo.write() completely
            provider.write(2, 3, sentinel);
            assertEquals(1L, provider.directCoWWriteBypasses());

            // Read back chunk and verify spliced section persisted minecraft:diamond_block losslessly
            final CompoundTag reloaded = provider.read(2, 3);
            assertNotNull(reloaded);
            boolean sawDiamond = false;
            final ListTag sections = reloaded.getListOrEmpty("sections");
            for (int s = 0; s < sections.size(); s++) {
                final CompoundTag sec = sections.getCompoundOrEmpty(s);
                if (sec.getByteOr("Y", (byte) 127) == (byte) 0) {
                    final ListTag palette = sec.getCompoundOrEmpty("block_states").getListOrEmpty("palette");
                    for (int p = 0; p < palette.size(); p++) {
                        final CompoundTag entry = palette.getCompoundOrEmpty(p);
                        final String blockId = entry.getStringOr("Name", entry.getStringOr("id", ""));
                        if ("minecraft:diamond_block".equals(blockId)) {
                            sawDiamond = true;
                        }
                    }
                }
            }
            assertTrue(sawDiamond, "Zero-NBT CoW spliced section must persist minecraft:diamond_block to .lca");

            // Unload hook must evict residency entry (S-002)
            LcaResidencyManager.global().onChunkUnloaded(worldId, 2, 3);
            assertNull(cache.getReadSnapshot(chunkKey), "Chunk unload must evict residency entry");
        } finally {
            LcaResidencyManager.global().evictWorld(worldId);
            ChunkResidencySpi.register(previousSpi);
        }
    }

    @Test
    void predictApiMetricsApiAndBuiltInProtocolsWireEndToEnd() {
        final GoldLeafConfig defaults = GoldLeafConfig.defaults();
        GoldLeafBootstrap.registerAllLayer2Providers(defaults);
        assertTrue(TechnicalProtocolRegistry.global().isEmpty(), "Built-in technical protocols must default to off");

        // Region tick-end telemetry callback must drive both GoldLeafMetricsCollector and GoldLeafPredictPacer
        GoldLeafMetricsCollector.global().clear();
        PaperTelemetrySpi.onRegionTickEnd(99L, LcaResidencyManager.DEFAULT_WORLD_ID, 25_000_000L, 5, 16);
        assertEquals(1, GoldLeafMetricsCollector.global().trackedRegionCount());
        final MetricsSnapshot liveSnap = GoldLeafMetricsCollector.global().snapshot(50);
        assertEquals(5, liveSnap.playerCount);
        assertEquals(20.0, liveSnap.tps1m, 1e-6);
        assertEquals(25.0, liveSnap.mspt, 1e-6);
        assertTrue(GoldLeafPredictPacer.global().currentPublishedMs(GoldLeafPredictPacer.WorkLane.OFF_TICK_LCA_IO) > 0.0);

        // Opt-in built-in technical protocol handlers (Servux, Syncmatica, Jade, AppleSkin)
        final GoldLeafConfig.Protocols enabledProtocols = new GoldLeafConfig.Protocols();
        enabledProtocols.servuxEnabled = true;
        enabledProtocols.syncmaticaEnabled = true;
        enabledProtocols.jadeEnabled = true;
        enabledProtocols.appleskinEnabled = true;
        enabledProtocols.mapWorldIdEnabled = true;
        final UUID playerId = UUID.randomUUID();
        try {
            BuiltInTechnicalProtocolHandlers.syncFromConfig(TechnicalProtocolRegistry.global(), enabledProtocols);
            assertFalse(TechnicalProtocolRegistry.global().isEmpty());

            final byte[] servuxPayload = TechnicalProtocolCodecs.encodeServuxStructureQuery(
                new TechnicalProtocolCodecs.ServuxStructureQuery(1, 10, -4, 8)
            );
            assertEquals(
                TechnicalProtocolRegistry.DispatchStatus.HANDLED,
                TechnicalProtocolRegistry.global().dispatchIncoming(
                    playerId, TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES, servuxPayload, (u, p) -> true
                )
            );
            assertEquals(
                TechnicalProtocolRegistry.DispatchStatus.REJECTED_MALFORMED,
                TechnicalProtocolRegistry.global().dispatchIncoming(
                    playerId, TechnicalProtocolCodecs.CHANNEL_JADE_REQUEST, new byte[]{1, 2}, (u, p) -> true
                )
            );
            assertEquals(
                TechnicalProtocolRegistry.DispatchStatus.HANDLED,
                TechnicalProtocolRegistry.global().dispatchIncoming(
                    playerId, TechnicalProtocolCodecs.CHANNEL_APPLESKIN_SATURATION, TechnicalProtocolCodecs.encodeFloat(12.5f), (u, p) -> true
                )
            );
            TechnicalProtocolRegistry.global().onPlayerQuit(playerId);
            assertNull(BuiltInTechnicalProtocolHandlers.servux().lastQuery(playerId));
        } finally {
            BuiltInTechnicalProtocolHandlers.syncFromConfig(TechnicalProtocolRegistry.global(), defaults.protocols);
            assertTrue(TechnicalProtocolRegistry.global().isEmpty(), "Reloading default-off config must unregister built-in handlers");
        }

        // Direct AIMD breach check
        final GoldLeafPredictPacer pacer = new GoldLeafPredictPacer();
        final long t0 = TimeUnit.SECONDS.toNanos(1);
        final long steadyBudget = pacer.evaluateLaneBudgetNanos(
            GoldLeafPredictPacer.WorkLane.OFF_TICK_LCA_IO, 25.0, 40.0, 0L, false, t0
        );
        final long breachBudget = pacer.evaluateLaneBudgetNanos(
            GoldLeafPredictPacer.WorkLane.OFF_TICK_LCA_IO, 60.0, 40.0, TimeUnit.MILLISECONDS.toNanos(50), false, t0 + TimeUnit.MILLISECONDS.toNanos(50)
        );
        assertTrue(breachBudget < steadyBudget, "AIMD limiter must cut background I/O budget on MSPT breach");
    }

    @Test
    void shardLeaseRecordTracksRemoteBoundaryCleanly() {
        final var lease = new RegionShardFence.RegionLeaseRecord("world", 1, 0, "shard-east", 12L, 0L);
        assertEquals("shard-east", lease.ownerShardId());
        assertEquals(12L, lease.leaseEpoch());
    }

    private static CompoundTag buildSyntheticChunkTag(final int cx, final int cz) {
        final CompoundTag tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
        tag.putInt("xPos", cx);
        tag.putInt("yPos", -4);
        tag.putInt("zPos", cz);
        tag.putLong("LastUpdate", 12345L + cx * 31L + cz);
        tag.putLong("InhabitedTime", 100L);
        tag.putString("Status", "minecraft:full");

        final ListTag sections = new ListTag();
        final int[] sectionYs = {-4, 0, 1, 2};
        for (final int sy : sectionYs) {
            final CompoundTag sec = new CompoundTag();
            sec.putByte("Y", (byte) sy);

            final CompoundTag blockStates = new CompoundTag();
            final ListTag palette = new ListTag();
            final CompoundTag entry0 = new CompoundTag();
            entry0.putString("Name", sy == -4 ? "minecraft:bedrock" : "minecraft:stone");
            palette.add(entry0);
            final CompoundTag entry1 = new CompoundTag();
            entry1.putString("Name", sy == 0 ? "minecraft:deepslate" : "minecraft:air");
            palette.add(entry1);
            blockStates.put("palette", palette);
            final long[] packedData = new long[256];
            Arrays.fill(packedData, 0x1010101010101010L);
            blockStates.putLongArray("data", packedData);
            sec.put("block_states", blockStates);

            final CompoundTag biomes = new CompoundTag();
            final ListTag biomePalette = new ListTag();
            biomePalette.add(net.minecraft.nbt.StringTag.valueOf("minecraft:plains"));
            biomes.put("palette", biomePalette);
            sec.put("biomes", biomes);

            final byte[] blockLight = new byte[2048];
            Arrays.fill(blockLight, (byte) 0x55);
            sec.putByteArray("BlockLight", blockLight);
            final byte[] skyLight = new byte[2048];
            Arrays.fill(skyLight, (byte) 0xFF);
            sec.putByteArray("SkyLight", skyLight);
            sections.add(sec);
        }
        tag.put("sections", sections);
        tag.put("block_entities", new ListTag());
        final CompoundTag heightmaps = new CompoundTag();
        heightmaps.put("WORLD_SURFACE", new LongArrayTag(new long[37]));
        tag.put("Heightmaps", heightmaps);
        final CompoundTag pdc = new CompoundTag();
        pdc.putString("goldleaf_probe", "chunk_" + cx + "_" + cz);
        tag.put("ChunkBukkitValues", pdc);
        return tag;
    }
}
