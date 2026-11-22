package dev.leafcraft.goldleaf.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EntityTrackerScalingTest {

    @Test
    void forEachSnapshotMutatesSourceSafelyAndClearsScratchReferences() throws Exception {
        final EntityTrackerScaling scaling = new EntityTrackerScaling();
        final Set<String> seenBy = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            seenBy.add("player-" + i);
        }

        // Simulate moonrise$tick purge: remove odd-numbered players while iterating
        scaling.forEachSnapshot(seenBy, p -> {
            final int id = Integer.parseInt(p.substring("player-".length()));
            if ((id & 1) == 1) {
                seenBy.remove(p);
            }
        });

        assertEquals(10, seenBy.size());
        for (int i = 0; i < 20; i += 2) {
            assertTrue(seenBy.contains("player-" + i));
        }

        // Verify scratch array is reused and every slot was nulled out (S-002)
        final int capAfterFirst = scaling.scratchCapacity();
        assertTrue(capAfterFirst >= 20);
        assertAllScratchSlotsNull(scaling);

        // Simulate moonrise$clearPlayers
        scaling.forEachSnapshot(seenBy, seenBy::remove);
        assertTrue(seenBy.isEmpty());
        assertEquals(capAfterFirst, scaling.scratchCapacity());
        assertAllScratchSlotsNull(scaling);
    }

    @Test
    void forEachSnapshotClearsScratchEvenWhenCallbackThrows() throws Exception {
        final EntityTrackerScaling scaling = new EntityTrackerScaling();
        final Set<String> seenBy = new HashSet<>(Set.of("a", "b", "c"));

        assertThrows(IllegalStateException.class, () -> scaling.forEachSnapshot(seenBy, elem -> {
            if ("b".equals(elem)) {
                throw new IllegalStateException("simulated disconnect fault");
            }
        }));

        assertAllScratchSlotsNull(scaling);
    }

    @Test
    void observeSpatialStateTracksPositionRangeAndChunkEpoch() {
        final EntityTrackerScaling scaling = new EntityTrackerScaling();
        assertEquals(0L, scaling.dirtyEpoch());

        assertTrue(scaling.observeSpatialState(10.0, 64.0, -5.0, 48, 1L));
        assertEquals(1L, scaling.dirtyEpoch());

        // Identical state does not advance dirty epoch
        assertFalse(scaling.observeSpatialState(10.0, 64.0, -5.0, 48, 1L));
        assertEquals(1L, scaling.dirtyEpoch());

        // Entity movement advances epoch
        assertTrue(scaling.observeSpatialState(10.25, 64.0, -5.0, 48, 1L));
        assertEquals(2L, scaling.dirtyEpoch());

        // Chunk player-list update advances epoch
        assertTrue(scaling.observeSpatialState(10.25, 64.0, -5.0, 48, 2L));
        assertEquals(3L, scaling.dirtyEpoch());
    }

    private static void assertAllScratchSlotsNull(final EntityTrackerScaling scaling) throws Exception {
        final Field scratchField = EntityTrackerScaling.class.getDeclaredField("scratch");
        scratchField.setAccessible(true);
        final Object[] arr = (Object[]) scratchField.get(scaling);
        if (arr != null) {
            for (final Object o : arr) {
                assertEquals(null, o, "Scratch slot must be nulled after snapshot pass (S-002)");
            }
        }
    }
}
