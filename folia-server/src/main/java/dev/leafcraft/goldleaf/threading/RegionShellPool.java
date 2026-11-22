package dev.leafcraft.goldleaf.threading;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegions;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Bounded per-world pool for empty, synchronously reset {@link RegionizedWorldData} shells.
 *
 * <p>Eliminates repetitive allocation and GC churn when ephemeral regions are created and
 * destroyed during rapid player exploration or chunk-ticket thrashing ({@code goldleaf-ADR-003} §2).
 * Only strictly empty shells are accepted and every lazy subsystem reference and mutable field is
 * cleared via {@link RegionizedWorldData#resetForPool()} before entering the pool.</p>
 */
public final class RegionShellPool {

    public static final int DEFAULT_MAX_POOLED_SHELLS = 16;

    private static final Map<ServerLevel, RegionShellPool> POOLS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private final int maxSize;
    private final ConcurrentLinkedDeque<RegionizedWorldData> pool = new ConcurrentLinkedDeque<>();
    private final AtomicInteger currentSize = new AtomicInteger();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong rejectedNonEmpty = new AtomicLong();

    public RegionShellPool(final int maxSize) {
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize must be >= 0, got " + maxSize);
        }
        this.maxSize = maxSize;
    }

    public static RegionShellPool forWorld(final ServerLevel world) {
        synchronized (POOLS) {
            return POOLS.computeIfAbsent(world, ignored -> new RegionShellPool(DEFAULT_MAX_POOLED_SHELLS));
        }
    }

    public static RegionizedWorldData acquire(
        final ServerLevel world,
        final TickRegions.TickRegionData regionData
    ) {
        return forWorld(world).acquireShell(world, regionData);
    }

    public static boolean release(final @Nullable RegionizedWorldData shell) {
        if (shell == null || shell.world == null) {
            return false;
        }
        return forWorld(shell.world).releaseShell(shell);
    }

    public RegionizedWorldData acquireShell(
        final ServerLevel world,
        final TickRegions.TickRegionData regionData
    ) {
        final RegionizedWorldData pooled = this.pool.pollFirst();
        if (pooled != null) {
            this.currentSize.decrementAndGet();
            this.hits.incrementAndGet();
            pooled.rebind(regionData);
            return pooled;
        }
        this.misses.incrementAndGet();
        return new RegionizedWorldData(world, regionData);
    }

    public boolean releaseShell(final @Nullable RegionizedWorldData shell) {
        if (shell == null || this.maxSize == 0) {
            return false;
        }
        if (!shell.canPool()) {
            this.rejectedNonEmpty.incrementAndGet();
            return false;
        }
        if (this.currentSize.incrementAndGet() > this.maxSize) {
            this.currentSize.decrementAndGet();
            return false;
        }
        shell.resetForPool();
        this.pool.offerFirst(shell);
        return true;
    }

    public int getMaxSize() {
        return this.maxSize;
    }

    public int getPooledCount() {
        return this.currentSize.get();
    }

    public long getPoolHits() {
        return this.hits.get();
    }

    public long getPoolMisses() {
        return this.misses.get();
    }

    public long getRejectedNonEmpty() {
        return this.rejectedNonEmpty.get();
    }

    public void clear() {
        while (this.pool.pollFirst() != null) {
            this.currentSize.decrementAndGet();
        }
    }

    public record PoolTelemetry(long hits, long misses, long rejectedNonEmpty, int pooledCount) {
    }

    public PoolTelemetry telemetrySnapshot() {
        return new PoolTelemetry(
            this.hits.get(),
            this.misses.get(),
            this.rejectedNonEmpty.get(),
            this.currentSize.get()
        );
    }

    public static PoolTelemetry globalTelemetry() {
        long totalHits = 0L;
        long totalMisses = 0L;
        long totalRejected = 0L;
        int totalPooled = 0;
        synchronized (POOLS) {
            for (final RegionShellPool pool : POOLS.values()) {
                totalHits += pool.getPoolHits();
                totalMisses += pool.getPoolMisses();
                totalRejected += pool.getRejectedNonEmpty();
                totalPooled += pool.getPooledCount();
            }
        }
        return new PoolTelemetry(totalHits, totalMisses, totalRejected, totalPooled);
    }
}
