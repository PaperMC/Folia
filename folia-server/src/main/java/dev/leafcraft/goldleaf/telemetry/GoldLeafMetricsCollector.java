package dev.leafcraft.goldleaf.telemetry;

import dev.leafcraft.goldleaf.threading.RegionShellPool;
import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 2 per-region telemetry collector backed by {@code api/metrics-api}
 * ({@code goldleaf-ADR-001} Stage 5).
 *
 * <p>Records lock-free per-region TPS/MSPT/player/queue samples from Folia region threads and
 * publishes immutable {@link MetricsSnapshot} instances with full {@link FoliaRegionSample}
 * breakdowns for diagnostics, admin commands, and external observability plugins.</p>
 */
public final class GoldLeafMetricsCollector {

    private static final GoldLeafMetricsCollector GLOBAL = new GoldLeafMetricsCollector();

    private final ConcurrentHashMap<String, FoliaRegionSample> regionSamples = new ConcurrentHashMap<>();

    public static GoldLeafMetricsCollector global() {
        return GLOBAL;
    }

    public void recordRegionSample(
        final String regionId,
        final double tps1m,
        final double mspt,
        final int playerCount,
        final int queueDepth,
        final long sampleCount,
        final long ageMillis
    ) {
        Objects.requireNonNull(regionId, "regionId");
        this.regionSamples.put(
            regionId,
            new FoliaRegionSample(regionId, tps1m, mspt, Math.max(0, playerCount), Math.max(0, queueDepth), sampleCount, ageMillis)
        );
    }

    public void removeRegion(final String regionId) {
        if (regionId != null) {
            this.regionSamples.remove(regionId);
        }
    }

    public void clear() {
        this.regionSamples.clear();
    }

    public int trackedRegionCount() {
        return this.regionSamples.size();
    }

    /**
     * Returns aggregated {@link RegionShellPool} reuse counters across all active worlds
     * ({@code goldleaf-ADR-003} §4).
     */
    public RegionShellPool.PoolTelemetry regionShellPoolTelemetry() {
        return RegionShellPool.globalTelemetry();
    }

    /**
     * Assembles an immutable {@link MetricsSnapshot} aggregating worst-case region MSPT and
     * minimum region TPS across all tracked regions alongside current JVM heap metrics.
     */
    public MetricsSnapshot snapshot(final int softCap) {
        final List<FoliaRegionSample> regions = new ArrayList<>(this.regionSamples.size());
        double minTps = Double.POSITIVE_INFINITY;
        double maxMspt = Double.NEGATIVE_INFINITY;
        int totalPlayers = 0;

        for (final Map.Entry<String, FoliaRegionSample> entry : this.regionSamples.entrySet()) {
            final FoliaRegionSample sample = entry.getValue();
            regions.add(sample);
            totalPlayers += sample.playerCount;
            if (!Double.isNaN(sample.tps1m) && sample.tps1m < minTps) {
                minTps = sample.tps1m;
            }
            if (!Double.isNaN(sample.mspt) && sample.mspt > maxMspt) {
                maxMspt = sample.mspt;
            }
        }

        final double aggTps = Double.isInfinite(minTps) ? MetricsSnapshot.UNSAMPLED : minTps;
        final double aggMspt = Double.isInfinite(maxMspt) ? MetricsSnapshot.UNSAMPLED : maxMspt;
        final Runtime rt = Runtime.getRuntime();
        final long heapUsed = rt.totalMemory() - rt.freeMemory();
        final long heapMax = rt.maxMemory();

        return new MetricsSnapshot(
            aggTps,
            aggTps,
            aggTps,
            aggMspt,
            totalPlayers,
            Math.max(0, softCap),
            heapUsed,
            heapMax,
            System.currentTimeMillis(),
            regions
        );
    }
}
