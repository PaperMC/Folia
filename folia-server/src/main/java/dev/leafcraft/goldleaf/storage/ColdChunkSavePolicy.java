package dev.leafcraft.goldleaf.storage;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;

/**
 * Opt-in persistence policy that skips writing freshly generated, unmodified, consistently unvisited
 * ("cold") chunks to disk ({@code storage.skip-save-unmodified-cold-chunks}).
 *
 * <p>When GPU chunk generation approaches or exceeds cold disk load speed, persisting untouched
 * exploration or dependency-apron chunks wastes disk space and save I/O. Any chunk loaded from
 * disk, mutated after generation (blocks, block entities, PDC), warmed by player proximity
 * ({@code inhabitedTime >= minInhabitedTicks}), or revisited {@code >= minRevisits} times
 * automatically graduates to normal persistence with zero data loss.</p>
 *
 * <p>Revisit tracking uses a bounded 65,536-slot saturating counter sketch per world (256 KiB,
 * zero allocation, lock-free across Folia region tick threads, {@code S-002} / {@code S-005}).</p>
 *
 * @governedBy goldleaf-ADR-005
 * @trace S-002, S-004, S-005
 */
public final class ColdChunkSavePolicy {

    private static final int SKETCH_SIZE = 1 << 16;
    private static final int SKETCH_MASK = SKETCH_SIZE - 1;
    private static final int MAX_REVISIT_COUNT = 255;

    private static final ColdChunkSavePolicy GLOBAL = new ColdChunkSavePolicy();
    private static final ThreadLocal<int[]> NATURAL_TICK_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private final ConcurrentHashMap<String, AtomicIntegerArray> revisitSketches = new ConcurrentHashMap<>();
    private final LongAdder skippedLevelChunkSaves = new LongAdder();
    private final LongAdder skippedProtoChunkSaves = new LongAdder();
    private final LongAdder persistedModified = new LongAdder();
    private final LongAdder persistedWarm = new LongAdder();
    private final LongAdder persistedRevisited = new LongAdder();

    public static ColdChunkSavePolicy global() {
        return GLOBAL;
    }

    public boolean isEnabled() {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        return cfg != null && cfg.storage != null && cfg.storage.skipSaveUnmodifiedColdChunks;
    }

    public boolean isIgnoreNaturalTicksEnabled() {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        return cfg != null
            && cfg.storage != null
            && cfg.storage.skipSaveUnmodifiedColdChunks
            && cfg.storage.coldChunkIgnoreNaturalTicks;
    }

    public static void enterNaturalTick() {
        NATURAL_TICK_DEPTH.get()[0]++;
    }

    public static void exitNaturalTick() {
        final int[] depth = NATURAL_TICK_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    public boolean shouldIgnoreNaturalTickModification() {
        return isIgnoreNaturalTicksEnabled() && NATURAL_TICK_DEPTH.get()[0] > 0;
    }

    /**
     * Records that a chunk was freshly generated in the given world so repeated regenerations of
     * the same coordinates graduate it to persistent storage once {@code minRevisits} is reached.
     */
    public int recordFreshGeneration(final @Nullable String worldId, final long chunkKey) {
        final String key = worldId != null ? worldId : "default";
        final AtomicIntegerArray sketch = this.revisitSketches.computeIfAbsent(
            key,
            k -> new AtomicIntegerArray(SKETCH_SIZE)
        );
        final int slot = hashSlot(chunkKey);
        return sketch.updateAndGet(slot, prev -> prev < MAX_REVISIT_COUNT ? prev + 1 : prev);
    }

    public int revisitCount(final @Nullable String worldId, final long chunkKey) {
        final String key = worldId != null ? worldId : "default";
        final AtomicIntegerArray sketch = this.revisitSketches.get(key);
        return sketch != null ? sketch.get(hashSlot(chunkKey)) : 0;
    }

    /**
     * Evaluates whether saving the given chunk should be skipped using active {@link GoldLeafConfig}.
     */
    public boolean shouldSkipSave(
        final @Nullable String worldId,
        final boolean isLevelChunk,
        final boolean generatedFresh,
        final boolean postGenModified,
        final boolean pdcDirty,
        final long inhabitedTime,
        final long chunkKey
    ) {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        if (cfg == null || cfg.storage == null || !cfg.storage.skipSaveUnmodifiedColdChunks) {
            return false;
        }
        return shouldSkipSave(
            worldId,
            isLevelChunk,
            generatedFresh,
            postGenModified,
            pdcDirty,
            inhabitedTime,
            chunkKey,
            true,
            cfg.storage.coldChunkMinInhabitedTicks,
            cfg.storage.coldChunkMinRevisits
        );
    }

    /**
     * Pure decision method for both runtime evaluation and unit tests.
     */
    public boolean shouldSkipSave(
        final @Nullable String worldId,
        final boolean isLevelChunk,
        final boolean generatedFresh,
        final boolean postGenModified,
        final boolean pdcDirty,
        final long inhabitedTime,
        final long chunkKey,
        final boolean enabled,
        final long minInhabitedTicks,
        final int minRevisits
    ) {
        if (!enabled) {
            return false;
        }
        if (!isLevelChunk) {
            // Unpromoted ProtoChunks (e.g. perimeter apron chunks) that were never loaded from disk
            // and never mutated can be safely skipped during shutdown.
            if (!generatedFresh || postGenModified || pdcDirty) {
                return false;
            }
            this.skippedProtoChunkSaves.increment();
            return true;
        }
        if (!generatedFresh) {
            return false;
        }
        if (postGenModified || pdcDirty) {
            this.persistedModified.increment();
            return false;
        }
        if (minInhabitedTicks >= 0 && inhabitedTime >= minInhabitedTicks) {
            this.persistedWarm.increment();
            return false;
        }
        if (minRevisits > 0 && revisitCount(worldId, chunkKey) >= minRevisits) {
            this.persistedRevisited.increment();
            return false;
        }
        this.skippedLevelChunkSaves.increment();
        return true;
    }

    public long skippedLevelChunkSaves() {
        return this.skippedLevelChunkSaves.sum();
    }

    public long skippedProtoChunkSaves() {
        return this.skippedProtoChunkSaves.sum();
    }

    public long persistedModified() {
        return this.persistedModified.sum();
    }

    public long persistedWarm() {
        return this.persistedWarm.sum();
    }

    public long persistedRevisited() {
        return this.persistedRevisited.sum();
    }

    public void resetTelemetry() {
        this.skippedLevelChunkSaves.reset();
        this.skippedProtoChunkSaves.reset();
        this.persistedModified.reset();
        this.persistedWarm.reset();
        this.persistedRevisited.reset();
        this.revisitSketches.clear();
    }

    public String formatSummary() {
        return String.format(
            "[GoldLeaf-ColdChunkSave] skippedLevel=%d, skippedProto=%d, persistedModified=%d, persistedWarm=%d, persistedRevisited=%d",
            skippedLevelChunkSaves(),
            skippedProtoChunkSaves(),
            persistedModified(),
            persistedWarm(),
            persistedRevisited()
        );
    }

    private static int hashSlot(final long chunkKey) {
        long z = chunkKey + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (int) (z & SKETCH_MASK);
    }
}
