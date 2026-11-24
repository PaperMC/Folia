package ca.spottedleaf.moonrise.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class PlayerChunkPrioritySpiTest {

    @AfterEach
    void tearDown() {
        PlayerChunkPrioritySpi.resetDefault();
    }

    private static long pack(final int x, final int z) {
        return (((long) z) << 32) | (x & 0xFFFF_FFFFL);
    }

    @Test
    void disabledSnapshotMatchesPureEuclideanDistance() {
        final PlayerChunkPrioritySpi.HeadingSnapshot snap = new PlayerChunkPrioritySpi.HeadingSnapshot();
        snap.snapshot(0.0F, 1L, PlayerChunkPrioritySpi.Settings.DISABLED);
        assertFalse(snap.isActive());

        // Equal distance ahead (+Z) and behind (-Z) tie at 0 when disabled
        assertEquals(0, PlayerChunkPrioritySpi.compareOffsets(0, 10, pack(0, 10), 0, -10, pack(0, -10), snap));
        // Closer behind beats farther ahead when disabled
        assertTrue(PlayerChunkPrioritySpi.compareOffsets(0, -8, pack(0, -8), 0, 10, pack(0, 10), snap) < 0);
    }

    @Test
    void facingForwardPrioritizesForwardOverSideAndBehindWhilePreservingInnerRing() {
        final PlayerChunkPrioritySpi.HeadingSnapshot snap = new PlayerChunkPrioritySpi.HeadingSnapshot();
        // Minecraft yaw = 0 faces +Z (0, +1)
        snap.snapshot(0.0F, 1L, PlayerChunkPrioritySpi.Settings.DEFAULT);
        assertTrue(snap.isActive());
        assertEquals(0.0D, snap.dirX(), 1.0E-6D);
        assertEquals(1.0D, snap.dirZ(), 1.0E-6D);

        // Inner radial safety ring (r <= 2) always beats outer chunks even straight ahead
        assertTrue(PlayerChunkPrioritySpi.compareOffsets(0, -2, pack(0, -2), 0, 3, pack(0, 3), snap) < 0);
        assertTrue(PlayerChunkPrioritySpi.compareOffsets(0, -1, pack(0, -1), 0, 2, pack(0, 2), snap) < 0);

        // Outer elliptical field: ahead beats side, side beats behind at equal radius
        assertTrue(PlayerChunkPrioritySpi.compareOffsets(0, 10, pack(0, 10), 10, 0, pack(10, 0), snap) < 0);
        assertTrue(PlayerChunkPrioritySpi.compareOffsets(10, 0, pack(10, 0), 0, -10, pack(0, -10), snap) < 0);

        // Farther ahead (r=16, score=16*(1-0.45)=8.8) beats closer behind (r=8, score=8*(1+0.45)=11.6)
        assertTrue(PlayerChunkPrioritySpi.compareOffsets(0, 16, pack(0, 16), 0, -8, pack(0, -8), snap) < 0);

        // Monotonicity along forward ray: closer ahead always precedes farther ahead (zero holes)
        for (int r = 3; r < 32; r++) {
            assertTrue(PlayerChunkPrioritySpi.compareOffsets(0, r, pack(0, r), 0, r + 1, pack(0, r + 1), snap) < 0);
        }
    }

    @Test
    void highSpeedVelocityDominatesOverFacingYawAndIncreasesEccentricity() {
        final PlayerChunkPrioritySpi.HeadingSnapshot snap = new PlayerChunkPrioritySpi.HeadingSnapshot();
        // Move fast along +X (2.0 blocks/tick) while looking +Z (yaw = 0)
        for (int tick = 0; tick <= 15; tick++) {
            snap.updateMotion(tick * 2.0D, 0.0D);
        }
        snap.snapshot(0.0F, 16L, PlayerChunkPrioritySpi.Settings.DEFAULT);

        assertTrue(snap.dirX() > 0.90D, "Velocity should dominate heading during fast flight, got dirX=" + snap.dirX());
        assertEquals(PlayerChunkPrioritySpi.DEFAULT_VELOCITY_BIAS, snap.bias(), 1.0E-6D);
        // Chunk along +X flight path beats chunk along +Z look direction at equal distance
        assertTrue(PlayerChunkPrioritySpi.compareOffsets(12, 0, pack(12, 0), 0, 12, pack(0, 12), snap) < 0);
    }

    @Test
    void teleportResetsVelocityEmaAndTurnHysteresisEnforcesCooldown() {
        final PlayerChunkPrioritySpi.HeadingSnapshot snap = new PlayerChunkPrioritySpi.HeadingSnapshot();
        snap.updateMotion(0.0D, 0.0D);
        snap.updateMotion(1000.0D, 0.0D); // Teleport (> 16 blocks/tick) must reset EMA
        snap.snapshot(0.0F, 100L, PlayerChunkPrioritySpi.Settings.DEFAULT);
        assertEquals(0.0D, snap.dirX(), 1.0E-6D);
        assertEquals(1.0D, snap.dirZ(), 1.0E-6D);
        assertEquals(PlayerChunkPrioritySpi.DEFAULT_LOOK_BIAS, snap.bias(), 1.0E-6D);

        // Within 10-tick cooldown -> false even for 180-degree turn
        assertFalse(snap.shouldReprioritizeOnTurn(180.0F, 105L, PlayerChunkPrioritySpi.Settings.DEFAULT));
        // After cooldown, small 10-degree turn (< 25 deg threshold) -> false
        assertFalse(snap.shouldReprioritizeOnTurn(10.0F, 115L, PlayerChunkPrioritySpi.Settings.DEFAULT));
        // After cooldown, 45-degree turn (> 25 deg threshold) -> true
        assertTrue(snap.shouldReprioritizeOnTurn(45.0F, 115L, PlayerChunkPrioritySpi.Settings.DEFAULT));
    }

    @Test
    void strictTotalOrderInFastutilLongHeapPriorityQueue() {
        final PlayerChunkPrioritySpi.HeadingSnapshot snap = new PlayerChunkPrioritySpi.HeadingSnapshot();
        snap.snapshot(37.5F, 1L, PlayerChunkPrioritySpi.Settings.DEFAULT);

        final LongHeapPriorityQueue heap = new LongHeapPriorityQueue((c1, c2) -> {
            final int x1 = (int) c1;
            final int z1 = (int) (c1 >>> 32);
            final int x2 = (int) c2;
            final int z2 = (int) (c2 >>> 32);
            return PlayerChunkPrioritySpi.compareOffsets(x1, z1, c1, x2, z2, c2, snap);
        });
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                heap.enqueue(pack(dx, dz));
            }
        }
        long prev = heap.dequeueLong();
        int count = 1;
        while (!heap.isEmpty()) {
            final long curr = heap.dequeueLong();
            final int px = (int) prev;
            final int pz = (int) (prev >>> 32);
            final int cx = (int) curr;
            final int cz = (int) (curr >>> 32);
            assertTrue(
                PlayerChunkPrioritySpi.compareOffsets(px, pz, prev, cx, cz, curr, snap) <= 0,
                "Heap dequeue order violated strict weak ordering"
            );
            prev = curr;
            count++;
        }
        assertEquals(17 * 17, count);
    }

    @Test
    void rejectsOutOfBoundsSettingsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> PlayerChunkPrioritySpi.Settings.of(true, -0.1D, 0.5D, 2, 25.0D));
        assertThrows(IllegalArgumentException.class, () -> PlayerChunkPrioritySpi.Settings.of(true, 0.9D, 0.5D, 2, 25.0D));
        assertThrows(IllegalArgumentException.class, () -> PlayerChunkPrioritySpi.Settings.of(true, 0.4D, 0.95D, 2, 25.0D));
        assertThrows(IllegalArgumentException.class, () -> PlayerChunkPrioritySpi.Settings.of(true, 0.4D, 0.7D, 9, 25.0D));
        assertThrows(IllegalArgumentException.class, () -> PlayerChunkPrioritySpi.Settings.of(true, 0.4D, 0.7D, 2, 2.0D));
    }
}
