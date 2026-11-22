package dev.leafcraft.goldleaf.storage;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.scheduler.TieredChunkReadRouter;
import dev.leafcraft.goldleaf.worldgen.CompactLcaChunkFrameBuilder;
import dev.leafcraft.goldleaf.worldgen.CompactSectionWritebackBuffer;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcaChunkResidencyCacheTest {

    private static RawChunkFrameSnapshot dummyFrame(final int dataVersion, final int sectionCount) {
        final int len = Math.max(128, 32 + sectionCount * 2);
        return new RawChunkFrameSnapshot(
            new byte[len],
            dataVersion,
            -4,
            sectionCount,
            len,
            new int[0]
        );
    }

    @Test
    void packsAndUnpacks4ByteDirtyCellAndRejectsOutOfRange() {
        final int packed = LcaChunkResidencyCache.packDirtyCell(4095, 12345);
        assertEquals(4095, LcaChunkResidencyCache.unpackLocalPos12(packed));
        assertEquals(12345, LcaChunkResidencyCache.unpackRuntimeStateId(packed));

        final int zeroPacked = LcaChunkResidencyCache.packDirtyCell(0, 0);
        assertNotEquals(0, zeroPacked, "pos12+1 encoding keeps packed cell non-zero");
        assertEquals(0, LcaChunkResidencyCache.unpackLocalPos12(zeroPacked));
        assertEquals(0, LcaChunkResidencyCache.unpackRuntimeStateId(zeroPacked));

        assertThrows(IllegalArgumentException.class, () -> LcaChunkResidencyCache.packDirtyCell(4096, 1));
        assertThrows(IllegalArgumentException.class, () -> LcaChunkResidencyCache.packDirtyCell(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> LcaChunkResidencyCache.packDirtyCell(0, 0x80000));
    }

    @Test
    void isolatesFrozenTier1SnapshotFromSubsequentLiveRegionEdits() {
        final LcaChunkResidencyCache cache = new LcaChunkResidencyCache();
        final long key = ChunkPos.pack(3, -5);
        cache.installBaseFrame(key, dummyFrame(4189, 24));

        // Clean baseline -> Tier 0
        final LcaChunkResidencyCache.FrozenReadSnapshot clean = cache.getReadSnapshot(key);
        assertNotNull(clean);
        assertTrue(clean.isClean());

        // Live edit + tick-end freeze -> Tier 1
        assertTrue(cache.recordDirtyVoxel(key, 2, 100, 777));
        final LcaChunkResidencyCache.FrozenReadSnapshot frozen1 = cache.freezeTickEndSnapshot(key);
        assertNotNull(frozen1);
        assertFalse(frozen1.isClean());
        assertEquals(777, frozen1.lookupDirtyRuntimeState(2, 100));
        assertEquals(-1, frozen1.lookupDirtyRuntimeState(2, 101));

        // Subsequent live edit must NOT mutate the previously published FrozenReadSnapshot
        assertTrue(cache.recordDirtyVoxel(key, 2, 100, 999));
        assertTrue(cache.recordDirtyVoxel(key, 2, 101, 555));
        assertEquals(777, frozen1.lookupDirtyRuntimeState(2, 100));
        assertEquals(-1, frozen1.lookupDirtyRuntimeState(2, 101));

        // Next tick-end freeze reflects both updates
        final LcaChunkResidencyCache.FrozenReadSnapshot frozen2 = cache.freezeTickEndSnapshot(key);
        assertNotNull(frozen2);
        assertEquals(999, frozen2.lookupDirtyRuntimeState(2, 100));
        assertEquals(555, frozen2.lookupDirtyRuntimeState(2, 101));
    }

    @Test
    void unloadFencePreventsInFlightAsyncCommitFromResurrectingEvictedChunk() {
        final LcaChunkResidencyCache cache = new LcaChunkResidencyCache();
        final long key = ChunkPos.pack(10, 20);
        cache.installBaseFrame(key, dummyFrame(4189, 24));

        final long commitEpoch = cache.beginCommitEpoch(key);
        assertTrue(commitEpoch > 0L);

        // Chunk unloads before async CoW commit finishes
        cache.markUnloadedAndEvict(key);
        assertEquals(0, cache.residentCount());
        assertNull(cache.getReadSnapshot(key));

        // Late async commit must fail closed and never resurrect entry (S-002)
        assertFalse(cache.completeCommitSwap(key, commitEpoch, dummyFrame(4189, 24)));
        assertEquals(0, cache.residentCount());
        assertNull(cache.getReadSnapshot(key));
    }

    @Test
    void tieredRouterRoutesTier0Tier1Tier3OffThreadAndFallback(@TempDir final Path tempDir) throws Exception {
        final Path regionDir = tempDir.resolve("region");
        final GoldLeafConfig.Storage storageConfig = new GoldLeafConfig.Storage();
        storageConfig.format = "lca";

        final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "goldleaf-test-io");
            t.setDaemon(true);
            return t;
        });

        try (LcaRegionStorageProvider provider = LcaRegionStorageProvider.open(regionDir, storageConfig)) {
            // Persist chunk (5, 7) to .lca disk so Tier 3 can probe it cold
            final CompoundTag chunkTag = new CompoundTag();
            chunkTag.putInt("DataVersion", 4189);
            chunkTag.putInt("xPos", 5);
            chunkTag.putInt("yPos", -4);
            chunkTag.putInt("zPos", 7);
            chunkTag.putString("Status", "minecraft:full");
            chunkTag.put("sections", new ListTag());
            provider.write(5, 7, chunkTag);
            provider.flush();

            final LcaChunkResidencyCache cache = new LcaChunkResidencyCache();
            final TieredChunkReadRouter router = new TieredChunkReadRouter(cache, provider);

            // 1. Tier 0 (clean resident) -> completes inline on caller thread
            final long tier0Key = ChunkPos.pack(1, 1);
            cache.installBaseFrame(tier0Key, dummyFrame(4189, 24));
            final TieredChunkReadRouter.RoutedReadResult<String> r0 = router.probeChunk(
                tier0Key, 1, 1, s -> 0,
                snap -> "clean:" + snap.baseFrame().dataVersion(),
                frame -> "cold",
                () -> "fallback",
                ioExecutor
            ).get();
            assertEquals(TieredChunkReadRouter.ReadTier.TIER_0_CLEAN_LOCAL, r0.tier());
            assertEquals("clean:4189", r0.value());

            // 2. Tier 1 (dirty frozen resident) -> completes inline on caller thread
            assertTrue(cache.recordDirtyVoxel(tier0Key, 0, 12, 88));
            cache.freezeTickEndSnapshot(tier0Key);
            final TieredChunkReadRouter.RoutedReadResult<Integer> r1 = router.probeChunk(
                tier0Key, 1, 1, s -> 0,
                snap -> snap.lookupDirtyRuntimeState(0, 12),
                frame -> -1,
                () -> -2,
                ioExecutor
            ).get();
            assertEquals(TieredChunkReadRouter.ReadTier.TIER_1_DIRTY_FROZEN_LOCAL, r1.tier());
            assertEquals(88, r1.value());

            // 3. Tier 3 (unloaded chunk on .lca disk) -> executes strictly on asyncIoExecutor (S-005)
            final AtomicReference<String> ioThreadName = new AtomicReference<>();
            final TieredChunkReadRouter.RoutedReadResult<Integer> r3 = router.probeChunk(
                ChunkPos.pack(5, 7), 5, 7, s -> 0,
                snap -> -1,
                frame -> {
                    ioThreadName.set(Thread.currentThread().getName());
                    return frame.dataVersion();
                },
                () -> -2,
                ioExecutor
            ).get();
            assertEquals(TieredChunkReadRouter.ReadTier.TIER_3_COLD_DISK, r3.tier());
            assertEquals(4189, r3.value());
            assertEquals("goldleaf-test-io", ioThreadName.get());

            // 4. Absent chunk -> falls back cleanly to Tier 2 supplier
            final TieredChunkReadRouter.RoutedReadResult<String> rFallback = router.probeChunk(
                ChunkPos.pack(99, 99), 99, 99, s -> 0,
                snap -> "resident",
                frame -> "cold",
                () -> "fallback-hit",
                ioExecutor
            ).get();
            assertEquals(TieredChunkReadRouter.ReadTier.TIER_2_LOCAL_INTERRUPT, rFallback.tier());
            assertEquals("fallback-hit", rFallback.value());
        } finally {
            ioExecutor.shutdownNow();
        }
    }

    @Test
    void readsMode10AndUniformSectionsInO1AndSplicesDirtyOverlayViaCoW() {
        final int sectionCount = 4;
        final int minSectionY = -4;
        final int[] runtimeIds = {0, 1, 34, 50}; // AIR, STONE, WATER, LAVA
        final CompactSectionWritebackBuffer dmaBuf = new CompactSectionWritebackBuffer(sectionCount);
        final IntBuffer words = dmaBuf.wordsDirectBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        final ShortBuffer meta = dmaBuf.metaDirectBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();

        // Section 0: uniform STONE (code 1)
        for (int i = 0; i < 256; i++) {
            words.put(i, 0x5555_5555);
        }
        meta.put(0, (short) 4096);
        meta.put(1, (short) 0);
        meta.put(2, (short) (1 << 1));

        // Section 1: mixed (ly < 8 -> STONE (1), ly == 8..11 -> WATER (2), ly >= 12 -> AIR (0), plus one DEEP_FLUID (3) at (3,0,5))
        int sec1NonEmpty = 0;
        int sec1Fluid = 0;
        int sec1Mask = 0;
        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                int packed = 0;
                for (int lx = 0; lx < 16; lx++) {
                    int code;
                    if (lx == 3 && ly == 0 && lz == 5) {
                        code = 3;
                    } else if (ly < 8) {
                        code = 1;
                    } else if (ly < 12) {
                        code = 2;
                    } else {
                        code = 0;
                    }
                    packed |= (code << (lx << 1));
                    if (code != 0) sec1NonEmpty++;
                    if (code >= 2) sec1Fluid++;
                    sec1Mask |= (1 << code);
                }
                words.put((1 << 8) + (ly << 4) + lz, packed);
            }
        }
        meta.put(4, (short) sec1NonEmpty);
        meta.put(5, (short) sec1Fluid);
        meta.put(6, (short) sec1Mask);

        // Section 2: mixed untouched section (single STONE voxel at (7,4,9), rest AIR)
        words.put((2 << 8) + (4 << 4) + 9, 1 << (7 << 1));
        meta.put(8, (short) 1);
        meta.put(9, (short) 0);
        meta.put(10, (short) ((1 << 0) | (1 << 1)));

        // Section 3: uniform AIR (code 0)
        meta.put(12, (short) 0);
        meta.put(13, (short) 0);
        meta.put(14, (short) (1 << 0));

        final CompactLcaChunkFrameBuilder.BuildResult built = CompactLcaChunkFrameBuilder.buildChunkFrame(
            dmaBuf, 0, 7, -3, minSectionY, sectionCount, 5023, runtimeIds, true
        );
        assertEquals(1, built.uniformAirSections());
        assertEquals(1, built.uniformNonAirSections());
        assertEquals(2, built.mixedSections());
        assertEquals(CompactLcaChunkFrameBuilder.STATUS_ORDINAL_TERRAIN, built.snapshot().statusOrdinal());

        // Verify CBIT T6b compliance via anvil-api's LcaSectionBacking.fromColdSectionFrame
        final byte[] sec1RuntimeSlice = built.snapshot().sliceSectionFrameWithRuntimePalette(1, null);
        assertTrue(sec1RuntimeSlice.length > 0);
        final LcaSectionBacking parsedSec1 = LcaSectionBacking.fromColdSectionFrame(
            sec1RuntimeSlice, 0, -1, 0, 0, id -> id != 0, id -> false, id -> false
        );
        assertEquals(50, parsedSec1.getStateId(3, 0, 5));
        assertEquals(1, parsedSec1.getStateId(0, 7, 0));
        assertEquals(34, parsedSec1.getStateId(0, 8, 0));
        assertEquals(0, parsedSec1.getStateId(0, 12, 0));

        // Install into LcaChunkResidencyCache and verify O(1) Tier 0 reads
        final LcaChunkResidencyCache cache = new LcaChunkResidencyCache();
        final long key = ChunkPos.pack(7, -3);
        cache.installBaseFrame(key, built.snapshot());
        final LcaChunkResidencyCache.FrozenReadSnapshot clean = cache.getReadSnapshot(key);
        assertNotNull(clean);
        assertTrue(clean.isClean());
        assertEquals(1, clean.readRuntimeStateId(0, 11, 15, 2));
        assertEquals(50, clean.readRuntimeStateId(1, 3, 0, 5));
        assertEquals(34, clean.readRuntimeStateId(1, 15, 11, 15));
        assertEquals(0, clean.readRuntimeStateId(1, 0, 15, 0));
        assertEquals(1, clean.readRuntimeStateId(2, 7, 4, 9));
        assertEquals(0, clean.readRuntimeStateId(3, 4, 4, 4));

        // Record dirty edits introducing brand-new runtime states (grass=42, oak_log=99) in sections 1 & 3
        final int posA = (12 << 8) | (2 << 4) | 1;
        final int posB = (3 << 8) | (5 << 4) | 6;
        assertTrue(cache.recordDirtyVoxel(key, 1, posA, 42));
        assertTrue(cache.recordDirtyVoxel(key, 3, posB, 99));
        // Live region-thread read sees edits immediately; old clean FrozenReadSnapshot stays isolated
        assertEquals(42, cache.readLiveRuntimeStateId(key, 1, 1, 12, 2));
        assertEquals(99, cache.readLiveRuntimeStateId(key, 3, 6, 3, 5));
        assertEquals(0, clean.readRuntimeStateId(1, 1, 12, 2));
        assertEquals(0, clean.readRuntimeStateId(3, 6, 3, 5));

        // Save untouched section 2 raw bytes before CoW splice
        final byte[] sec2Before = built.snapshot().sliceSectionFrame(2);

        // Compact dirty overlay via section-spliced CoW
        assertTrue(cache.compactDirtySnapshot(key));
        final LcaChunkResidencyCache.FrozenReadSnapshot compacted = cache.getReadSnapshot(key);
        assertNotNull(compacted);
        assertTrue(compacted.isClean(), "Expected compacted snapshot to be clean");
        assertEquals(1, compacted.readRuntimeStateId(0, 11, 15, 2));
        assertEquals(50, compacted.readRuntimeStateId(1, 3, 0, 5));
        assertEquals(42, compacted.readRuntimeStateId(1, 1, 12, 2));
        assertEquals(1, compacted.readRuntimeStateId(2, 7, 4, 9));
        assertEquals(99, compacted.readRuntimeStateId(3, 6, 3, 5));
        assertEquals(0, compacted.readRuntimeStateId(3, 0, 0, 0));

        // Untouched section 2 must be copied verbatim byte-for-byte
        assertTrue(java.util.Arrays.equals(sec2Before, compacted.baseFrame().sliceSectionFrame(2)));

        // Compacted dirty sections 1 & 3 must also pass CBIT T6b via LcaSectionBacking.fromColdSectionFrame
        final LcaSectionBacking postSec1 = LcaSectionBacking.fromColdSectionFrame(
            compacted.baseFrame().sliceSectionFrameWithRuntimePalette(1, null),
            0, -1, 0, 0, id -> id != 0, id -> false, id -> false
        );
        assertEquals(42, postSec1.getStateId(1, 12, 2));
        assertEquals(50, postSec1.getStateId(3, 0, 5));

        final LcaSectionBacking postSec3 = LcaSectionBacking.fromColdSectionFrame(
            compacted.baseFrame().sliceSectionFrameWithRuntimePalette(3, null),
            0, -1, 0, 0, id -> id != 0, id -> false, id -> false
        );
        assertEquals(99, postSec3.getStateId(6, 3, 5));
        assertEquals(0, postSec3.getStateId(0, 0, 0));
    }
}
