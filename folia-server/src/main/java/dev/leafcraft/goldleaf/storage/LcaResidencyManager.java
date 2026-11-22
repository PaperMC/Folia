package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.spi.ChunkResidencySpi;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 per-world registry of {@link LcaChunkResidencyCache} instances implementing the Layer 1
 * {@link ChunkResidencySpi} ({@code goldleaf-ADR-001} Stage 2 Tier 2, {@code goldleaf-ADR-005}).
 *
 * <p>Coordinates cold {@code .lca} frame installation on async chunk load, O(1) sparse dirty-cell
 * tracking on region-thread block edits, tick-end immutable snapshot freezes, and
 * {@code UNLOADED_BIT}-fenced eviction on chunk unload ({@code S-002}).</p>
 */
public final class LcaResidencyManager implements ChunkResidencySpi {

    /** Canonical default Overworld dimension identifier shared across storage, residency, and network caches. */
    public static final String DEFAULT_WORLD_ID = "minecraft:overworld";

    private static final LcaResidencyManager GLOBAL = new LcaResidencyManager(LcaChunkResidencyCache.DEFAULT_MAX_RESIDENT_CHUNKS);

    private final int maxResidentChunksPerWorld;
    private final ConcurrentHashMap<String, LcaChunkResidencyCache> worldCaches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LcaRegionStorageProvider> storageProviders = new ConcurrentHashMap<>();
    private final AtomicLong totalBaseFramesInstalled = new AtomicLong();
    private final AtomicLong totalDirtyVoxelsRecorded = new AtomicLong();
    private final AtomicLong totalChunksEvicted = new AtomicLong();

    public LcaResidencyManager(final int maxResidentChunksPerWorld) {
        if (maxResidentChunksPerWorld <= 0) {
            throw new IllegalArgumentException("maxResidentChunksPerWorld must be > 0, got " + maxResidentChunksPerWorld);
        }
        this.maxResidentChunksPerWorld = maxResidentChunksPerWorld;
    }

    public static LcaResidencyManager global() {
        return GLOBAL;
    }

    public LcaChunkResidencyCache cacheForWorld(final String worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return this.worldCaches.computeIfAbsent(worldId, ignored -> new LcaChunkResidencyCache(this.maxResidentChunksPerWorld));
    }

    public @Nullable LcaChunkResidencyCache getCacheIfPresent(final String worldId) {
        return worldId == null ? null : this.worldCaches.get(worldId);
    }

    public void installBaseFrame(
        final String worldId,
        final int chunkX,
        final int chunkZ,
        final RawChunkFrameSnapshot frame
    ) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(frame, "frame");
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        cacheForWorld(worldId).installBaseFrame(key, frame);
        this.totalBaseFramesInstalled.incrementAndGet();
    }

    public boolean releaseBaseFrameIfClean(final String worldId, final int chunkX, final int chunkZ) {
        final LcaChunkResidencyCache cache = this.worldCaches.get(worldId);
        if (cache == null) {
            return false;
        }
        return cache.releaseBaseFrameIfClean(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    @Override
    public void onChunkBlockEdited(
        final String worldId,
        final int chunkX,
        final int chunkZ,
        final int sectionIndex,
        final int localPos12,
        final int runtimeStateId
    ) {
        final LcaChunkResidencyCache cache = this.worldCaches.get(worldId);
        if (cache == null) {
            return;
        }
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        if (cache.recordDirtyVoxel(key, sectionIndex, localPos12, runtimeStateId)) {
            this.totalDirtyVoxelsRecorded.incrementAndGet();
        }
    }

    @Override
    public void onChunkUnloaded(final String worldId, final int chunkX, final int chunkZ) {
        final LcaChunkResidencyCache cache = this.worldCaches.get(worldId);
        if (cache == null) {
            return;
        }
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        cache.markUnloadedAndEvict(key);
        this.totalChunksEvicted.incrementAndGet();
    }

    @Override
    public void onRegionTickEnd(final String worldId, final long[] chunkKeys, final int count) {
        final LcaChunkResidencyCache cache = this.worldCaches.get(worldId);
        if (cache == null || chunkKeys == null) {
            return;
        }
        final int limit = Math.min(count, chunkKeys.length);
        for (int i = 0; i < limit; i++) {
            cache.freezeTickEndSnapshot(chunkKeys[i]);
        }
    }

    public void registerStorageProvider(final String worldId, final LcaRegionStorageProvider provider) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(provider, "provider");
        this.storageProviders.put(worldId, provider);
    }

    public void unregisterStorageProvider(final String worldId, final LcaRegionStorageProvider provider) {
        if (worldId != null && provider != null) {
            this.storageProviders.remove(worldId, provider);
        }
    }

    public @Nullable LcaRegionStorageProvider getStorageProvider(final String worldId) {
        return worldId == null ? null : this.storageProviders.get(worldId);
    }

    /**
     * Resolves the unique registered {@code worldId} holding a resident snapshot for
     * {@code (chunkX, chunkZ)} with an active {@link LcaRegionStorageProvider}, or {@code null}
     * if none or ambiguous across multiple dimensions.
     */
    public @Nullable String findUniqueResidentWorldWithStorage(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        String matched = null;
        for (final var entry : this.storageProviders.entrySet()) {
            final LcaChunkResidencyCache cache = this.worldCaches.get(entry.getKey());
            if (cache != null && cache.getReadSnapshot(key) != null) {
                if (matched != null) {
                    return null;
                }
                matched = entry.getKey();
            }
        }
        return matched;
    }

    public void evictWorld(final String worldId) {
        if (worldId != null) {
            this.worldCaches.remove(worldId);
            this.storageProviders.remove(worldId);
        }
    }

    public void clearAll() {
        this.worldCaches.clear();
        this.storageProviders.clear();
    }

    public long totalBaseFramesInstalled() {
        return this.totalBaseFramesInstalled.get();
    }

    public long totalDirtyVoxelsRecorded() {
        return this.totalDirtyVoxelsRecorded.get();
    }

    public long totalChunksEvicted() {
        return this.totalChunksEvicted.get();
    }
}
