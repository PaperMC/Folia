package dev.leafcraft.goldleaf.network;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Allocation-free scratch-array snapshot helper and dirty-epoch tracker for
 * {@code ChunkMap.TrackedEntity} ({@code goldleaf-ADR-009} §1.1).
 *
 * <p>Eliminates per-purge / per-clear {@code new ArrayList<>(this.seenBy)} Young-Gen allocations
 * while preserving {@code 100%} vanilla {@code seenBy} membership and visibility semantics. All
 * copied scratch slots are cleared in {@code finally} so disconnected player references are never
 * retained ({@code S-002}).</p>
 *
 * <p>Owned exclusively by a single {@code TrackedEntity} on its owning Folia region thread
 * ({@code F-001}..{@code F-003}).</p>
 */
public final class EntityTrackerScaling {

    private static final int INITIAL_CAPACITY = 8;
    private static final int MAX_RETAINED_CAPACITY = 512;

    private Object @Nullable [] scratch;

    private double lastEntityX = Double.NaN;
    private double lastEntityY = Double.NaN;
    private double lastEntityZ = Double.NaN;
    private int lastEffectiveRange = -1;
    private long lastChunkUpdate = Long.MIN_VALUE;
    private long dirtyEpoch;

    /**
     * Copies {@code source} into a reusable scratch array, invokes {@code action} for each element,
     * and nulls all occupied slots in {@code finally} so no element reference leaks ({@code S-002}).
     * Safe when {@code action} mutates {@code source} (e.g., {@code removePlayer}).
     */
    @SuppressWarnings("unchecked")
    public <T> void forEachSnapshot(final Collection<T> source, final Consumer<T> action) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(action, "action");
        final int size = source.size();
        if (size == 0) {
            return;
        }
        Object[] buf = this.scratch;
        if (buf == null || buf.length < size) {
            buf = new Object[Math.max(INITIAL_CAPACITY, nextPowerOfTwo(size))];
            if (buf.length <= MAX_RETAINED_CAPACITY) {
                this.scratch = buf;
            }
        }

        int count = 0;
        try {
            for (final T element : source) {
                if (count == buf.length) {
                    buf = Arrays.copyOf(buf, buf.length << 1);
                    if (buf.length <= MAX_RETAINED_CAPACITY) {
                        this.scratch = buf;
                    }
                }
                buf[count++] = element;
            }
            for (int i = 0; i < count; i++) {
                action.accept((T) buf[i]);
            }
        } finally {
            Arrays.fill(buf, 0, count, null);
        }
    }

    /**
     * Updates the entity's spatial and chunk-update dirty epoch. Returns {@code true} if the
     * entity moved, changed effective tracking range, or observed a new chunk player-map epoch.
     */
    public boolean observeSpatialState(
        final double entityX,
        final double entityY,
        final double entityZ,
        final int effectiveRange,
        final long chunkUpdateCount
    ) {
        if (Double.doubleToRawLongBits(entityX) != Double.doubleToRawLongBits(this.lastEntityX)
            || Double.doubleToRawLongBits(entityY) != Double.doubleToRawLongBits(this.lastEntityY)
            || Double.doubleToRawLongBits(entityZ) != Double.doubleToRawLongBits(this.lastEntityZ)
            || effectiveRange != this.lastEffectiveRange
            || chunkUpdateCount != this.lastChunkUpdate) {
            this.lastEntityX = entityX;
            this.lastEntityY = entityY;
            this.lastEntityZ = entityZ;
            this.lastEffectiveRange = effectiveRange;
            this.lastChunkUpdate = chunkUpdateCount;
            this.dirtyEpoch++;
            return true;
        }
        return false;
    }

    public long dirtyEpoch() {
        return this.dirtyEpoch;
    }

    public int scratchCapacity() {
        return this.scratch == null ? 0 : this.scratch.length;
    }

    public void reset() {
        if (this.scratch != null) {
            Arrays.fill(this.scratch, null);
            this.scratch = null;
        }
        this.lastEntityX = Double.NaN;
        this.lastEntityY = Double.NaN;
        this.lastEntityZ = Double.NaN;
        this.lastEffectiveRange = -1;
        this.lastChunkUpdate = Long.MIN_VALUE;
    }

    private static int nextPowerOfTwo(final int value) {
        int v = value - 1;
        v |= v >>> 1;
        v |= v >>> 2;
        v |= v >>> 4;
        v |= v >>> 8;
        v |= v >>> 16;
        return (v < 0) ? 1 : (v >= (1 << 30) ? (1 << 30) : v + 1);
    }
}
