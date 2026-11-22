package dev.leafcraft.goldleaf.threading;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.config.HardwareEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickThreadAutoTunerTest {

    @Test
    void explicitPaperGlobalThreadsAlwaysWins() {
        final GoldLeafConfig config = GoldLeafConfig.defaults();
        final HardwareEnvelope sixCore = HardwareEnvelope.of(6, 16L << 30);

        assertEquals(5, TickThreadAutoTuner.resolveTickThreads(5, config, sixCore));
        assertEquals(1, TickThreadAutoTuner.resolveTickThreads(1, config, sixCore));
    }

    @Test
    void unlocksMultiRegionParallelismOnFourToEightCoreWorkstations() {
        final GoldLeafConfig config = GoldLeafConfig.defaults();

        assertEquals(1, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(1, 8L << 30)));
        assertEquals(1, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(2, 8L << 30)));
        assertEquals(1, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(3, 8L << 30)));
        assertEquals(2, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(4, 8L << 30)));
        assertEquals(3, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(6, 16L << 30)));
        assertEquals(4, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(8, 16L << 30)));
        assertEquals(4, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(16, 32L << 30)));
        assertEquals(8, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(32, 64L << 30)));
        assertEquals(16, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(64, 128L << 30)));
    }

    @Test
    void curveIsMonotonicAcrossCoreCounts() {
        final GoldLeafConfig config = GoldLeafConfig.defaults();
        int previous = 1;
        for (int cores = 1; cores <= 128; cores++) {
            final int resolved = TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(cores, 16L << 30));
            assertTrue(resolved >= previous, "Expected monotonic threads at cores=" + cores + " (" + resolved + " < " + previous + ")");
            previous = resolved;
        }
    }

    @Test
    void disablingAutoTuneRestoresExactUpstreamFoliaFormula() {
        final GoldLeafConfig config = GoldLeafConfig.defaults();
        config.threading.autoTuneWorkstation = false;

        assertEquals(1, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(6, 16L << 30)));
        assertEquals(1, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(8, 16L << 30)));
        assertEquals(2, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(16, 32L << 30)));
        assertEquals(8, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(64, 128L << 30)));
    }

    @Test
    void appliesMinAndMaxAutoThreadBounds() {
        final GoldLeafConfig config = GoldLeafConfig.defaults();
        config.threading.minAutoThreads = 2;
        config.threading.maxAutoThreads = 3;

        assertEquals(2, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(2, 8L << 30)));
        assertEquals(3, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(8, 16L << 30)));
        assertEquals(3, TickThreadAutoTuner.resolveTickThreads(-1, config, HardwareEnvelope.of(64, 128L << 30)));
    }
}
