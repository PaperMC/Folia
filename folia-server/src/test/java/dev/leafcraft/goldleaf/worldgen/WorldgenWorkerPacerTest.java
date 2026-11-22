package dev.leafcraft.goldleaf.worldgen;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.config.HardwareEnvelope;
import dev.leafcraft.goldleaf.threading.TickThreadAutoTuner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldgenWorkerPacerTest {

    @Test
    void preservesExplicitConfiguredWorkerCount() {
        final GoldLeafConfig config = GoldLeafConfig.defaults();
        final HardwareEnvelope fourCore = HardwareEnvelope.of(4, 4L * 1024 * 1024 * 1024);
        assertEquals(6, WorldgenWorkerPacer.resolveWorkerThreads(6, config, fourCore));
    }

    @Test
    void fallsBackToUpstreamFormulaWhenAutoPacingDisabled() {
        final GoldLeafConfig config = GoldLeafConfig.defaults();
        config.worldgen.autoPaceGenerationWorkers = false;
        final HardwareEnvelope eightCore = HardwareEnvelope.of(8, 8L * 1024 * 1024 * 1024);
        assertEquals(
            WorldgenWorkerPacer.resolveUpstreamMoonriseWorkers(8),
            WorldgenWorkerPacer.resolveWorkerThreads(-1, config, eightCore)
        );
    }

    @Test
    void preservesTickThreadHeadroomOnSmallMachines() {
        final GoldLeafConfig config = GoldLeafConfig.defaults();
        for (final int cores : new int[] { 4, 6, 8 }) {
            final HardwareEnvelope env = HardwareEnvelope.of(cores, 8L * 1024 * 1024 * 1024);
            final int tickThreads = TickThreadAutoTuner.resolveTickThreads(-1, config, env);
            final int workerThreads = WorldgenWorkerPacer.resolveWorkerThreads(-1, config, env);
            assertTrue(workerThreads >= 1, "expected >= 1 worker on " + cores + " cores");
            assertTrue(
                tickThreads + workerThreads < cores,
                "expected tickThreads (" + tickThreads + ") + workerThreads (" + workerThreads
                    + ") < cores (" + cores + ")"
            );
        }
    }

    @Test
    void scalesCleanlyOnWorkstationAndCeilingHardware() {
        final GoldLeafConfig config = GoldLeafConfig.defaults();
        final HardwareEnvelope sixteenCore = HardwareEnvelope.of(16, 32L * 1024 * 1024 * 1024);
        final HardwareEnvelope thirtyTwoCore = HardwareEnvelope.of(32, 64L * 1024 * 1024 * 1024);

        final int workers16 = WorldgenWorkerPacer.resolveWorkerThreads(-1, config, sixteenCore);
        final int workers32 = WorldgenWorkerPacer.resolveWorkerThreads(-1, config, thirtyTwoCore);

        assertEquals(8, workers16);
        assertEquals(16, workers32);
    }
}
