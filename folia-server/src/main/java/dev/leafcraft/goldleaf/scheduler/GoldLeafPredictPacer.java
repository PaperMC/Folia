package dev.leafcraft.goldleaf.scheduler;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.leaf.predict.AimdLimiter;
import net.leaf.predict.TickTokenBucket;

/**
 * Layer 2 mechanics-neutral closed-loop work pacer backed by {@code api/predict-api}
 * ({@code goldleaf-ADR-001} Stage 5).
 *
 * <p>Strictly governs mechanics-neutral background work (chunk-generation dispatch, off-tick
 * {@code .lca} I/O, packet flushes, and shard-migration triggers) using lag-aware AIMD control
 * ({@link AimdLimiter}) and ceiling-adaptive per-tick nanosecond token budgets
 * ({@link TickTokenBucket}). Never throttles vanilla redstone, hoppers, pistons, or mob AI.</p>
 */
public final class GoldLeafPredictPacer {

    public enum WorkLane {
        CHUNK_GEN_DISPATCH,
        OFF_TICK_LCA_IO,
        PACKET_FLUSH,
        SHARD_MIGRATION
    }

    private static final long MS_TO_NS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final GoldLeafPredictPacer GLOBAL = new GoldLeafPredictPacer();

    private final AimdLimiter chunkGenLimiter;
    private final AimdLimiter lcaIoLimiter;
    private final AimdLimiter packetFlushLimiter;
    private final AimdLimiter shardMigrationLimiter;
    private final TickTokenBucket chunkGenBucket;
    private final TickTokenBucket lcaIoBucket;
    private final TickTokenBucket packetFlushBucket;
    private final TickTokenBucket shardMigrationBucket;

    public GoldLeafPredictPacer() {
        this.chunkGenLimiter = new AimdLimiter(1.0, 12.0);
        this.lcaIoLimiter = new AimdLimiter(1.0, 16.0);
        this.packetFlushLimiter = new AimdLimiter(2.0, 20.0);
        this.shardMigrationLimiter = new AimdLimiter(1.0, 8.0);

        this.chunkGenBucket = new TickTokenBucket();
        this.lcaIoBucket = new TickTokenBucket();
        this.packetFlushBucket = new TickTokenBucket();
        this.shardMigrationBucket = new TickTokenBucket();
    }

    public static GoldLeafPredictPacer global() {
        return GLOBAL;
    }

    /**
     * Evaluates one control step for {@code lane} given observed region tick duration ({@code msptMs})
     * against {@code targetMsptMs}, refills the lane's nanosecond token bucket, and returns the
     * updated per-tick budget in nanoseconds.
     */
    public synchronized long evaluateLaneBudgetNanos(
        final WorkLane lane,
        final double msptMs,
        final double targetMsptMs,
        final long signalLagNanos,
        final boolean stale,
        final long nowNanos
    ) {
        Objects.requireNonNull(lane, "lane");
        final AimdLimiter limiter = limiterFor(lane);
        final TickTokenBucket bucket = bucketFor(lane);
        final boolean breached = msptMs > targetMsptMs;
        final double publishedMs = limiter.evaluate(
            limiter.ceiling(),
            breached,
            Math.max(0L, signalLagNanos),
            stale,
            false,
            nowNanos
        );
        final long ceilingNanos = Math.max(MS_TO_NS, Math.round(publishedMs * MS_TO_NS));
        bucket.refill(ceilingNanos, nowNanos);
        return ceilingNanos;
    }

    /**
     * Charges {@code debitNanos} of mechanics-neutral work against {@code lane} and returns
     * {@code true} if the lane still has non-negative token headroom remaining.
     */
    public synchronized boolean chargeWork(final WorkLane lane, final long debitNanos, final long nowNanos) {
        Objects.requireNonNull(lane, "lane");
        final AimdLimiter limiter = limiterFor(lane);
        final long ceilingNanos = Math.max(MS_TO_NS, Math.round(limiter.publishedValue() * MS_TO_NS));
        return bucketFor(lane).charge(debitNanos, ceilingNanos, nowNanos) >= 0L;
    }

    public synchronized double currentPublishedMs(final WorkLane lane) {
        return limiterFor(Objects.requireNonNull(lane, "lane")).publishedValue();
    }

    private AimdLimiter limiterFor(final WorkLane lane) {
        return switch (lane) {
            case CHUNK_GEN_DISPATCH -> this.chunkGenLimiter;
            case OFF_TICK_LCA_IO -> this.lcaIoLimiter;
            case PACKET_FLUSH -> this.packetFlushLimiter;
            case SHARD_MIGRATION -> this.shardMigrationLimiter;
        };
    }

    private TickTokenBucket bucketFor(final WorkLane lane) {
        return switch (lane) {
            case CHUNK_GEN_DISPATCH -> this.chunkGenBucket;
            case OFF_TICK_LCA_IO -> this.lcaIoBucket;
            case PACKET_FLUSH -> this.packetFlushBucket;
            case SHARD_MIGRATION -> this.shardMigrationBucket;
        };
    }
}
