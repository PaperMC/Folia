package dev.leafcraft.goldleaf.telemetry;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkTelemetryAndMaskTest {

    @AfterEach
    void tearDown() {
        ChunkTickTelemetry.clearAllListeners();
    }

    @Test
    void telemetryIdleByDefaultAndEnforcesListenerCap() {
        assertFalse(ChunkTickTelemetry.isActive());

        final RegionTickTelemetryListener l1 = (r, w, x, z, p, ns) -> {};
        final RegionTickTelemetryListener l2 = (r, w, x, z, p, ns) -> {};
        final RegionTickTelemetryListener l3 = (r, w, x, z, p, ns) -> {};
        final RegionTickTelemetryListener l4 = (r, w, x, z, p, ns) -> {};
        final RegionTickTelemetryListener l5 = (r, w, x, z, p, ns) -> {};

        assertTrue(ChunkTickTelemetry.registerListener(l1));
        assertTrue(ChunkTickTelemetry.isActive());
        assertFalse(ChunkTickTelemetry.registerListener(l1)); // duplicate rejected
        assertTrue(ChunkTickTelemetry.registerListener(l2));
        assertTrue(ChunkTickTelemetry.registerListener(l3));
        assertTrue(ChunkTickTelemetry.registerListener(l4));
        assertFalse(ChunkTickTelemetry.registerListener(l5)); // default cap = 4
        assertEquals(4, ChunkTickTelemetry.listenerCount());

        ChunkTickTelemetry.clearAllListeners();
        assertFalse(ChunkTickTelemetry.isActive());
    }

    @Test
    void faultyTelemetryListenerIsQuarantinedWhileHealthyListenerContinues() {
        final AtomicInteger faultyCalls = new AtomicInteger();
        final AtomicLong healthyTotalNanos = new AtomicLong();

        final RegionTickTelemetryListener faulty = (r, w, x, z, p, ns) -> {
            faultyCalls.incrementAndGet();
            throw new IllegalStateException("simulated plugin bug");
        };
        final RegionTickTelemetryListener healthy = (r, w, x, z, p, ns) -> healthyTotalNanos.addAndGet(ns);

        assertTrue(ChunkTickTelemetry.registerListener(faulty));
        assertTrue(ChunkTickTelemetry.registerListener(healthy));

        ChunkTickTelemetry.dispatchPhaseSample(1L, "world", 3, -4, ChunkTickPhase.REDSTONE, 1_500L);
        ChunkTickTelemetry.dispatchPhaseSample(1L, "world", 3, -4, ChunkTickPhase.HOPPER, 2_500L);

        assertEquals(1, faultyCalls.get(), "faulty listener must be quarantined after first throw");
        assertEquals(4_000L, healthyTotalNanos.get(), "healthy listener must continue receiving samples");
        assertEquals(1, ChunkTickTelemetry.listenerCount());
    }

    @Test
    void activityCountersDrainAndResetCleanly() {
        final ChunkActivityCounters counters = new ChunkActivityCounters();
        counters.recordRedstoneUpdate();
        counters.recordRedstoneUpdate();
        counters.recordHopperTransfer();
        counters.recordPistonMove();
        counters.recordBlockGrowth();
        counters.recordEntityTick();

        final ChunkActivityCounters.Snapshot snap = counters.drainSnapshot();
        assertFalse(snap.isEmpty());
        assertEquals(2, snap.redstoneUpdates());
        assertEquals(1, snap.hopperTransfers());
        assertEquals(1, snap.pistonMoves());
        assertEquals(1, snap.blockGrowths());
        assertEquals(1, snap.entityTicks());

        assertTrue(counters.drainSnapshot().isEmpty());
    }

    @Test
    void executionMaskRespectsVanillaDefaultAndSpecialistOrPistonExemptions() {
        // Vanilla default (0) never suppresses
        assertFalse(ChunkExecutionMask.isSuppressed(ChunkExecutionMask.NONE, (byte) 0, ChunkExecutionMask.SUPPRESS_REDSTONE));
        assertFalse(ChunkExecutionMask.isSuppressed(ChunkExecutionMask.NONE, (byte) 0, ChunkExecutionMask.SUPPRESS_HOPPERS));
        assertFalse(ChunkExecutionMask.isSuppressed(ChunkExecutionMask.NONE, (byte) 0, ChunkExecutionMask.SUPPRESS_PISTONS));

        // Direct suppression without exemptions
        assertTrue(ChunkExecutionMask.isSuppressed(ChunkExecutionMask.SUPPRESS_HOPPERS, (byte) 0, ChunkExecutionMask.SUPPRESS_HOPPERS));
        assertTrue(ChunkExecutionMask.isSuppressed(ChunkExecutionMask.SUPPRESS_PISTONS, (byte) 0, ChunkExecutionMask.SUPPRESS_PISTONS));

        // Specialist hopper & piston contraption exemptions bypass suppression
        assertFalse(ChunkExecutionMask.isSuppressed(
            ChunkExecutionMask.SUPPRESS_HOPPERS | ChunkExecutionMask.VIRTUALIZED_BLACKBOX,
            ChunkExecutionMask.EXEMPT_SPECIALIST,
            ChunkExecutionMask.SUPPRESS_HOPPERS
        ));
        assertFalse(ChunkExecutionMask.isSuppressed(
            ChunkExecutionMask.SUPPRESS_PISTONS | ChunkExecutionMask.VIRTUALIZED_BLACKBOX,
            ChunkExecutionMask.EXEMPT_PISTON,
            ChunkExecutionMask.SUPPRESS_PISTONS
        ));

        // Virtualized blackbox suppresses non-exempt redstone
        assertTrue(ChunkExecutionMask.isSuppressed(
            ChunkExecutionMask.VIRTUALIZED_BLACKBOX,
            ChunkExecutionMask.EXEMPT_SPECIALIST,
            ChunkExecutionMask.SUPPRESS_REDSTONE
        ));
    }

    @Test
    void regionProximityViewAndAccumulatorBehaveDeterministically() {
        assertEquals(
            Double.POSITIVE_INFINITY,
            RegionProximityView.nearestPlayerDistanceSquared(new double[0], new double[0], 0, 0)
        );
        // Chunk (1, 0) center is (24.0, 8.0); player at (24.0, 12.0) is distance 4.0 -> distSq 16.0
        final double distSq = RegionProximityView.nearestPlayerDistanceSquared(
            new double[] { 100.0, 24.0 },
            new double[] { 100.0, 12.0 },
            1,
            0
        );
        assertEquals(16.0, distSq, 1e-6);

        final ChunkTickTelemetry.RegionAccumulator acc = new ChunkTickTelemetry.RegionAccumulator();
        acc.addPhaseNanos(ChunkTickPhase.ENTITY, 5_000L);
        acc.addPhaseNanos(ChunkTickPhase.ENTITY, 2_500L);
        assertEquals(7_500L, acc.getPhaseNanos(ChunkTickPhase.ENTITY));
        acc.reset();
        assertEquals(0L, acc.getPhaseNanos(ChunkTickPhase.ENTITY));
    }

    @Test
    void unregisteringOrQuarantiningListenerBumpsMaskEpochToInvalidateStaleMasks() {
        final long initialEpoch = ChunkTickTelemetry.currentMaskEpoch();
        final RegionTickTelemetryListener listener = (r, w, x, z, p, ns) -> {};
        assertTrue(ChunkTickTelemetry.registerListener(listener));
        assertEquals(initialEpoch, ChunkTickTelemetry.currentMaskEpoch());

        // Unregistering must monotonically bump the mask epoch (DSN-1)
        assertTrue(ChunkTickTelemetry.unregisterListener(listener));
        final long afterUnregister = ChunkTickTelemetry.currentMaskEpoch();
        assertTrue(afterUnregister > initialEpoch, "unregisterListener must bump maskEpoch");

        // Quarantine on throwing listener must also bump the mask epoch
        final RegionTickTelemetryListener faulty = (r, w, x, z, p, ns) -> {
            throw new RuntimeException("boom");
        };
        assertTrue(ChunkTickTelemetry.registerListener(faulty));
        ChunkTickTelemetry.dispatchPhaseSample(1L, "world", 0, 0, ChunkTickPhase.ENTITY, 100L);
        assertTrue(ChunkTickTelemetry.currentMaskEpoch() > afterUnregister, "quarantine must bump maskEpoch");
    }

    @Test
    void publicChunkTelemetryServiceBridgesPhaseListenersAndCleanUnregister() {
        final GoldLeafChunkTelemetryService service = GoldLeafChunkTelemetryService.instance();
        final AtomicLong receivedNanos = new AtomicLong();
        final io.papermc.paper.telemetry.ChunkTelemetryService.PhaseListener apiListener =
            (regionId, world, cx, cz, phase, nanos) -> {
                if (phase == io.papermc.paper.telemetry.ChunkTelemetryService.TickPhase.ENTITY) {
                    receivedNanos.addAndGet(nanos);
                }
            };

        assertTrue(service.isTelemetrySpiEnabled());
        assertTrue(service.registerPhaseListener(apiListener));
        assertTrue(service.isTelemetryActive());
        assertFalse(service.registerPhaseListener(apiListener)); // duplicate rejected

        ChunkTickTelemetry.dispatchPhaseSample(7L, "minecraft:overworld", 2, -3, ChunkTickPhase.ENTITY, 4_200L);
        assertEquals(4_200L, receivedNanos.get());

        assertTrue(service.unregisterPhaseListener(apiListener));
        assertFalse(service.isTelemetryActive());
    }
}
