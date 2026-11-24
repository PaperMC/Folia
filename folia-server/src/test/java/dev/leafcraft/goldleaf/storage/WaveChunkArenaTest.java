package dev.leafcraft.goldleaf.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveChunkArenaTest {

    @Test
    void stateTransitionsAndAbaSafeUnloadDuringSave() {
        final WaveChunkArena arena = new WaveChunkArena(64, 24);
        final int slot = arena.acquireForGpuGen(42L);
        assertTrue(slot >= 0);
        assertEquals(WaveChunkArena.STATE_GPU_GEN, arena.stateOf(slot));
        assertEquals(1, arena.activeSlots());
        assertEquals(42L, arena.chunkKeyOf(slot));

        final byte[] bufA = arena.sectionFlatBuffer(slot, 3);
        assertEquals(WaveChunkArena.FLAT_4BIT_SECTION_BYTES, bufA.length);
        assertSame(bufA, arena.sectionFlatBuffer(slot, 3), "Section buffer must be reused across accesses");

        assertTrue(arena.markLive(slot));
        assertEquals(WaveChunkArena.STATE_LIVE, arena.stateOf(slot));

        assertTrue(arena.tryBeginSave(slot));
        assertEquals(WaveChunkArena.STATE_SAVE_IN_FLIGHT, arena.stateOf(slot));

        // Unloading while save is in flight must pin the slot in STATE_UNLOADED_SAVING (S-002)
        assertTrue(arena.markUnloaded(slot));
        assertEquals(WaveChunkArena.STATE_UNLOADED_SAVING, arena.stateOf(slot));
        assertEquals(1, arena.activeSlots());
        assertEquals(1L, arena.deferredUnloadReleases());

        // Completing the save transitions STATE_UNLOADED_SAVING -> STATE_FREE
        assertTrue(arena.completeSave(slot));
        assertEquals(WaveChunkArena.STATE_FREE, arena.stateOf(slot));
        assertEquals(0, arena.activeSlots());
    }

    @Test
    void waveAllocationReusesFreedSlotsAndDetachesPastHighWaterMark() {
        final WaveChunkArena arena = new WaveChunkArena(64, 4);
        final int[] wave1 = new int[48];
        for (int i = 0; i < 48; i++) {
            wave1[i] = arena.acquireForGpuGen(i);
            assertTrue(wave1[i] >= 0);
            assertTrue(arena.markLive(wave1[i]));
        }
        assertTrue(arena.shouldDetachLongLived(), "75% occupancy must signal long-lived detach");

        // Free wave 1 -> wave 2 must reuse slots cleanly with zero exhaustion
        for (int i = 0; i < 48; i++) {
            assertTrue(arena.markUnloaded(wave1[i]));
        }
        assertEquals(0, arena.activeSlots());
        assertFalse(arena.shouldDetachLongLived());

        for (int i = 0; i < 64; i++) {
            final int slot = arena.acquireForGpuGen(1000L + i);
            assertTrue(slot >= 0);
        }
        assertEquals(64, arena.activeSlots());
        assertEquals(-1, arena.acquireForGpuGen(9999L), "Saturated arena must return -1 for heap fallback");
        assertEquals(1L, arena.exhaustionFallbacks());
    }
}
