package dev.leafcraft.goldleaf.worldgen;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainGenerationAcceleratorTest {

    @AfterEach
    void resetAccelerator() {
        TerrainGenerationAccelerator.resetDefault();
    }

    @Test
    void defaultAcceleratorIsNoOp() {
        TerrainGenerationAccelerator.resetDefault();
        assertTrue(TerrainGenerationAccelerator.get().isNoOp());
        assertFalse(TerrainGenerationAccelerator.isActive());
        assertEquals(0, TerrainGenerationAccelerator.WaveResult.noneHandled(8).handledCount());
    }

    @Test
    void waveResultValidatesBoundsAndCopiesArrayDefensively() {
        final boolean[] source = new boolean[] { true, false, true };
        final TerrainGenerationAccelerator.WaveResult result =
            new TerrainGenerationAccelerator.WaveResult(source, 100L, 50L, 25L);
        source[0] = false;

        assertTrue(result.isHandled(0));
        assertFalse(result.isHandled(1));
        assertTrue(result.isHandled(2));
        assertEquals(2, result.handledCount());

        assertThrows(
            IllegalArgumentException.class,
            () -> new TerrainGenerationAccelerator.WaveResult(new boolean[] { true }, -1L, 0L, 0L)
        );
    }

    @Test
    void parityModeParsesValidValuesAndFailsClosedOnInvalid() {
        assertEquals(
            TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT,
            TerrainGenerationAccelerator.ParityMode.fromConfig("vanilla-exact")
        );
        assertEquals(
            TerrainGenerationAccelerator.ParityMode.FAST,
            TerrainGenerationAccelerator.ParityMode.fromConfig("FAST")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TerrainGenerationAccelerator.ParityMode.fromConfig("ultra-unsafe")
        );
    }

    @Test
    void telemetryTracksWaveOccupancyAndTimingSeparately() {
        final WorldgenWaveTelemetry telemetry = new WorldgenWaveTelemetry();
        telemetry.recordBiomeWave(16, 16, 1_000L, 200L, 100L);
        telemetry.recordTerrainWave(24, 20, 4_000L, 800L, 500L);
        telemetry.recordTerrainWave(8, 8, 2_000L, 400L, 200L);

        final WorldgenWaveTelemetry.Snapshot snap = telemetry.snapshot();
        assertEquals(1L, snap.biomeWaves());
        assertEquals(16.0, snap.averageBiomeChunksPerWave());
        assertEquals(2L, snap.terrainWaves());
        assertEquals(16.0, snap.averageTerrainChunksPerWave());
        assertEquals(28L, snap.terrainChunksAccelerated());
        assertEquals(4L, snap.terrainChunksFallback());
        assertEquals(7_000L, snap.gpuDispatchNanos());
        assertEquals(1_400L, snap.readbackNanos());
        assertEquals(800L, snap.hostCpuNanos());
        assertEquals(0L, snap.dualExecutionViolations());
    }

    @Test
    void strictRequireAcceleratorFailsClosedWhenNoAcceleratorRegistered() {
        TerrainGenerationAccelerator.resetDefault();
        final WorldgenWaveTelemetry telemetry = new WorldgenWaveTelemetry();
        assertThrows(
            IllegalStateException.class,
            () -> TerrainGenerationAccelerator.dispatchSingleTerrain(null, null, true, telemetry)
        );
    }
}
