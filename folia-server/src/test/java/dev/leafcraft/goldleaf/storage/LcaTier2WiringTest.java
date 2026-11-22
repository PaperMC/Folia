package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.spi.ChunkResidencySpi;
import ca.spottedleaf.moonrise.spi.DirectSectionSerializationSpi;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcaTier2WiringTest {

    @AfterEach
    void cleanup() {
        DirectSectionSerializationSpi.resetDefault();
        ChunkResidencySpi.resetDefault();
        LcaResidencyManager.global().clearAll();
    }

    @Test
    void directSectionSerializerRegistersIntoMoonriseLayer1Spi() {
        final LcaDirectSectionSerializer serializer = LcaDirectSectionSerializer.createDefault();
        DirectSectionSerializer.register(serializer);
        assertSame(serializer, DirectSectionSerializationSpi.get());
        assertSame(serializer, DirectSectionSerializer.get());
        assertNull(serializer.trySerializeDirect(null));
        assertEquals(0L, serializer.directChunksSerialized());
    }

    @Test
    void residencyManagerTracksInstallEditFreezeAndUnloadEviction() {
        final LcaResidencyManager manager = new LcaResidencyManager(64);
        ChunkResidencySpi.register(manager);
        assertSame(manager, ChunkResidencySpi.get());

        // Build a minimal valid RawChunkFrameSnapshot
        final byte[] minimalFrame = new byte[128];
        final RawChunkFrameSnapshot frame = new RawChunkFrameSnapshot(
            minimalFrame,
            4189,
            -4,
            1,
            minimalFrame.length,
            new int[0]
        );
        final String worldId = "minecraft:overworld";
        manager.installBaseFrame(worldId, 3, -2, frame);
        assertEquals(1L, manager.totalBaseFramesInstalled());

        final int localPos12 = (5 << 8) | (4 << 4) | 2;
        manager.onChunkBlockEdited(worldId, 3, -2, 0, localPos12, 42);
        assertEquals(1L, manager.totalDirtyVoxelsRecorded());

        final LcaChunkResidencyCache cache = manager.getCacheIfPresent(worldId);
        assertNotNull(cache);
        final long key = CoordinateUtils.getChunkKey(3, -2);
        assertEquals(42, cache.readLiveRuntimeStateId(key, 0, 2, 5, 4));

        manager.onRegionTickEnd(worldId, new long[] { key }, 1);
        final var snap = cache.getReadSnapshot(key);
        assertNotNull(snap);
        assertTrue(!snap.isClean());

        // S-002 unload eviction removes entry cleanly
        manager.onChunkUnloaded(worldId, 3, -2);
        assertEquals(1L, manager.totalChunksEvicted());
        assertNull(cache.getReadSnapshot(key));
        assertEquals(-1, cache.readLiveRuntimeStateId(key, 0, 2, 5, 4));
    }
}
