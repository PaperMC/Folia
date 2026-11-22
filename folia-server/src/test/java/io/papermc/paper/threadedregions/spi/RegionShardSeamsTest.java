package io.papermc.paper.threadedregions.spi;

import io.papermc.paper.threadedregions.ThreadedRegionizer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionShardSeamsTest {

    @AfterEach
    void resetAllSpis() {
        RegionShardFence.resetDefault();
        RegionMigrationHook.resetDefault();
        DirtyVoxelHaloSink.resetDefault();
    }

    private static final class DummyRegionData
        implements ThreadedRegionizer.ThreadedRegionData<DummyRegionData, DummySectionData> {
        @Override
        public void split(
            final ThreadedRegionizer<DummyRegionData, DummySectionData> regioniser,
            final it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap<ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData>> regionToData,
            final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData>> dataSet
        ) {
        }

        @Override
        public void mergeInto(final ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData> into) {
        }
    }

    private static final class DummySectionData implements ThreadedRegionizer.ThreadedRegionSectionData {
    }

    private static ThreadedRegionizer<DummyRegionData, DummySectionData> createRegionizer() {
        return new ThreadedRegionizer<DummyRegionData, DummySectionData>(
            2,
            0.5,
            1,
            1,
            4,
            null,
            new ThreadedRegionizer.RegionCallbacks<DummyRegionData, DummySectionData>() {
                @Override
                public DummyRegionData createNewData(final ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData> region) {
                    return new DummyRegionData();
                }

                @Override
                public DummySectionData createNewSectionData(final int sectionX, final int sectionZ, final int sectionShift) {
                    return new DummySectionData();
                }

                @Override
                public void onRegionCreate(final ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData> region) {
                }

                @Override
                public void onRegionDestroy(final ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData> region) {
                }

                @Override
                public void onRegionActive(final ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData> region) {
                }

                @Override
                public void onRegionInactive(final ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData> region) {
                }

                @Override
                public void preMerge(
                    final ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData> from,
                    final ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData> into
                ) {
                }

                @Override
                public void preSplit(
                    final ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData> from,
                    final List<ThreadedRegionizer.ThreadedRegion<DummyRegionData, DummySectionData>> into
                ) {
                }
            }
        );
    }

    @Test
    void adjacentSectionsMergeByDefaultWithoutFence() {
        final ThreadedRegionizer<DummyRegionData, DummySectionData> regionizer = createRegionizer();
        regionizer.addChunk(0, 0);
        regionizer.addChunk(16, 0);

        final var r0 = regionizer.getRegionAtUnsynchronised(0, 0);
        final var r1 = regionizer.getRegionAtUnsynchronised(16, 0);
        assertSame(r0, r1, "Unfenced adjacent sections must merge normally");
    }

    @Test
    void shardFenceBlocksMergingAcrossRemoteLeaseBoundary() {
        RegionShardFence.register((world, sx, sz, nx, nz) -> (sx <= 0 && nx >= 1) || (sx >= 1 && nx <= 0));

        final ThreadedRegionizer<DummyRegionData, DummySectionData> regionizer = createRegionizer();
        regionizer.addChunk(0, 0);
        regionizer.addChunk(16, 0);

        final var r0 = regionizer.getRegionAtUnsynchronised(0, 0);
        final var r1 = regionizer.getRegionAtUnsynchronised(16, 0);
        assertNotSame(r0, r1, "Fenced sections across remote shard boundary must remain separate regions");
    }

    @Test
    void leaseRecordValidatesInvariantsAndExpiry() {
        final var lease = new RegionShardFence.RegionLeaseRecord("world", 2, -1, "shard-2", 7L, 5000L);
        assertFalse(lease.isExpiredAt(4999L));
        assertTrue(lease.isExpiredAt(5000L));
        assertThrows(IllegalArgumentException.class,
            () -> new RegionShardFence.RegionLeaseRecord("world", 0, 0, " ", 1L, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> new RegionShardFence.RegionLeaseRecord("world", 0, 0, "shard-1", -1L, 0L));
    }

    @Test
    void dirtyVoxelHaloPackRoundTripAndBounds() {
        final int packed = DirtyVoxelHaloSink.packDirtyVoxel(4095, 12345);
        assertEquals(4095, DirtyVoxelHaloSink.unpackLocalPos12(packed));
        assertEquals(12345, DirtyVoxelHaloSink.unpackRuntimeStateId(packed));
        assertThrows(IllegalArgumentException.class, () -> DirtyVoxelHaloSink.packDirtyVoxel(4096, 1));
        assertThrows(IllegalArgumentException.class, () -> DirtyVoxelHaloSink.packDirtyVoxel(0, 0x80000));
    }

    @Test
    void migrationHookReceivesLifecycleAndProxySwitchCalls() {
        final List<String> events = new ArrayList<>();
        final AtomicInteger switches = new AtomicInteger();
        RegionMigrationHook.register(new RegionMigrationHook() {
            @Override
            public void onPreMerge(final net.minecraft.server.level.ServerLevel world, final long fromId, final long intoId) {
                events.add("merge:" + fromId + "->" + intoId);
            }

            @Override
            public void onProxyPlayerSwitchRequest(
                final net.minecraft.server.level.ServerLevel world,
                final UUID playerId,
                final String targetShardId,
                final int targetChunkX,
                final int targetChunkZ
            ) {
                switches.incrementAndGet();
            }
        });

        final RegionMigrationHook hook = RegionMigrationHook.get();
        hook.onPreMerge(null, 1L, 2L);
        hook.onProxyPlayerSwitchRequest(null, UUID.randomUUID(), "shard-b", 32, -16);
        assertEquals(List.of("merge:1->2"), events);
        assertEquals(1, switches.get());
    }
}
