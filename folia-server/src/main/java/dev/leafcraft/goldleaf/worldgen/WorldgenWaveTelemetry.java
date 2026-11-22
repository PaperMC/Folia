package dev.leafcraft.goldleaf.worldgen;

import java.util.concurrent.atomic.LongAdder;

/**
 * Post-dispatch provenance and wave-occupancy telemetry for {@link TerrainGenerationAccelerator}
 * ({@code goldleaf-ADR-004} §2.4).
 *
 * <p>Records wave batch sizes, GPU dispatch time, readback time, host-CPU time, fallback counts,
 * and dual-execution invariant violations separately so GPU starvation and dual-execution
 * regressions are immediately detectable in benchmarks and acceptance tests. Telemetry is
 * observational only and never drives boot-time worker sizing ({@link WorldgenWorkerPacer}).</p>
 */
public final class WorldgenWaveTelemetry {

    public static final WorldgenWaveTelemetry GLOBAL = new WorldgenWaveTelemetry();

    private final LongAdder biomeWaves = new LongAdder();
    private final LongAdder biomeChunksCoalesced = new LongAdder();
    private final LongAdder biomeChunksAccelerated = new LongAdder();
    private final LongAdder biomeChunksFallback = new LongAdder();

    private final LongAdder terrainWaves = new LongAdder();
    private final LongAdder terrainChunksCoalesced = new LongAdder();
    private final LongAdder terrainChunksAccelerated = new LongAdder();
    private final LongAdder terrainChunksFallback = new LongAdder();

    private final LongAdder gpuDispatchNanos = new LongAdder();
    private final LongAdder readbackNanos = new LongAdder();
    private final LongAdder hostCpuNanos = new LongAdder();
    private final LongAdder dualExecutionViolations = new LongAdder();

    public record Snapshot(
        long biomeWaves,
        long biomeChunksCoalesced,
        long biomeChunksAccelerated,
        long biomeChunksFallback,
        long terrainWaves,
        long terrainChunksCoalesced,
        long terrainChunksAccelerated,
        long terrainChunksFallback,
        long gpuDispatchNanos,
        long readbackNanos,
        long hostCpuNanos,
        long dualExecutionViolations
    ) {

        public double averageBiomeChunksPerWave() {
            return this.biomeWaves == 0L ? 0.0 : (double) this.biomeChunksCoalesced / (double) this.biomeWaves;
        }

        public double averageTerrainChunksPerWave() {
            return this.terrainWaves == 0L ? 0.0 : (double) this.terrainChunksCoalesced / (double) this.terrainWaves;
        }

        public long totalWaves() {
            return this.biomeWaves + this.terrainWaves;
        }

        public long totalChunksCoalesced() {
            return this.biomeChunksCoalesced + this.terrainChunksCoalesced;
        }
    }

    public void recordBiomeWave(
        final int coalescedChunks,
        final int acceleratedChunks,
        final long gpuDispatchNanos,
        final long readbackNanos,
        final long hostCpuNanos
    ) {
        final int safeCoalesced = Math.max(0, coalescedChunks);
        final int safeAccelerated = Math.max(0, Math.min(acceleratedChunks, safeCoalesced));
        this.biomeWaves.increment();
        this.biomeChunksCoalesced.add(safeCoalesced);
        this.biomeChunksAccelerated.add(safeAccelerated);
        this.biomeChunksFallback.add(safeCoalesced - safeAccelerated);
        this.gpuDispatchNanos.add(Math.max(0L, gpuDispatchNanos));
        this.readbackNanos.add(Math.max(0L, readbackNanos));
        this.hostCpuNanos.add(Math.max(0L, hostCpuNanos));
    }

    public void recordTerrainWave(
        final int coalescedChunks,
        final int acceleratedChunks,
        final long gpuDispatchNanos,
        final long readbackNanos,
        final long hostCpuNanos
    ) {
        final int safeCoalesced = Math.max(0, coalescedChunks);
        final int safeAccelerated = Math.max(0, Math.min(acceleratedChunks, safeCoalesced));
        this.terrainWaves.increment();
        this.terrainChunksCoalesced.add(safeCoalesced);
        this.terrainChunksAccelerated.add(safeAccelerated);
        this.terrainChunksFallback.add(safeCoalesced - safeAccelerated);
        this.gpuDispatchNanos.add(Math.max(0L, gpuDispatchNanos));
        this.readbackNanos.add(Math.max(0L, readbackNanos));
        this.hostCpuNanos.add(Math.max(0L, hostCpuNanos));
    }

    public void recordDualExecutionViolation() {
        this.dualExecutionViolations.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.biomeWaves.sum(),
            this.biomeChunksCoalesced.sum(),
            this.biomeChunksAccelerated.sum(),
            this.biomeChunksFallback.sum(),
            this.terrainWaves.sum(),
            this.terrainChunksCoalesced.sum(),
            this.terrainChunksAccelerated.sum(),
            this.terrainChunksFallback.sum(),
            this.gpuDispatchNanos.sum(),
            this.readbackNanos.sum(),
            this.hostCpuNanos.sum(),
            this.dualExecutionViolations.sum()
        );
    }

    public void reset() {
        this.biomeWaves.reset();
        this.biomeChunksCoalesced.reset();
        this.biomeChunksAccelerated.reset();
        this.biomeChunksFallback.reset();
        this.terrainWaves.reset();
        this.terrainChunksCoalesced.reset();
        this.terrainChunksAccelerated.reset();
        this.terrainChunksFallback.reset();
        this.gpuDispatchNanos.reset();
        this.readbackNanos.reset();
        this.hostCpuNanos.reset();
        this.dualExecutionViolations.reset();
    }
}
