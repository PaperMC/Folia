package dev.leafcraft.goldleaf.storage;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wave-aligned pooled chunk slot arena with a compact 1-byte per-slot state table
 * ({@code byte[slotCount]}, L1-cache resident at {@code 1 KiB} for {@code 1,024} slots).
 *
 * <p>Lines up waves of chunk deallocation on the trailing edge with waves of GPU generation
 * allocation on the leading edge while remaining ABA-safe across asynchronous disk saves
 * ({@code S-002}, {@code S-005}, {@code goldleaf-ADR-005}, {@code goldleaf-ADR-017}):
 * <ul>
 *   <li>{@code 0 = STATE_FREE}: available for immediate wave reuse</li>
 *   <li>{@code 1 = STATE_GPU_GEN}: owned by an in-flight GPU terrain/surface/feature wave</li>
 *   <li>{@code 2 = STATE_LIVE}: attached to a live chunk during CPU features, lighting, or viewing</li>
 *   <li>{@code 3 = STATE_SAVE_IN_FLIGHT}: pinned while Mode A serialization / NVMe {@code pwrite} runs</li>
 *   <li>{@code 4 = STATE_UNLOADED_SAVING}: chunk unloaded while save was still in flight; reclaimed to
 *       {@code STATE_FREE} only when {@link #completeSave(int)} fires</li>
 * </ul>
 *
 * @governedBy goldleaf-ADR-005, goldleaf-ADR-017, anvil-api-ADR-003
 * @trace S-002, S-004, S-005
 */
public final class WaveChunkArena {

    public static final byte STATE_FREE = 0;
    public static final byte STATE_GPU_GEN = 1;
    public static final byte STATE_LIVE = 2;
    public static final byte STATE_SAVE_IN_FLIGHT = 3;
    public static final byte STATE_UNLOADED_SAVING = 4;

    public static final int DEFAULT_SLOT_COUNT = 1024;
    public static final int DEFAULT_SECTIONS_PER_SLOT = 24;
    public static final int FLAT_4BIT_SECTION_BYTES = 2048;

    private static final VarHandle BYTE_ARRAY_VH = MethodHandles.arrayElementVarHandle(byte[].class);

    private static final WaveChunkArena GLOBAL = new WaveChunkArena(DEFAULT_SLOT_COUNT, DEFAULT_SECTIONS_PER_SLOT);

    private final int slotCount;
    private final int slotMask;
    private final int sectionsPerSlot;
    private final byte[] states;
    private final long[] chunkKeys;
    private final long[] acquiredNanos;
    private final byte[][][] sectionFlatBuffers;
    private final ConcurrentHashMap<Long, Integer> chunkKeyToSlot = new ConcurrentHashMap<>(1024);
    private final AtomicInteger allocCursor = new AtomicInteger(0);
    private final AtomicInteger activeSlots = new AtomicInteger(0);
    private final AtomicLong totalAcquires = new AtomicLong(0);
    private final AtomicLong deferredUnloadReleases = new AtomicLong(0);
    private final AtomicLong exhaustionFallbacks = new AtomicLong(0);

    public WaveChunkArena(final int requestedSlots, final int sectionsPerSlot) {
        final int clamped = Math.max(64, Math.min(4096, requestedSlots));
        int pow2 = 1;
        while (pow2 < clamped) {
            pow2 <<= 1;
        }
        this.slotCount = pow2;
        this.slotMask = pow2 - 1;
        this.sectionsPerSlot = Math.max(1, sectionsPerSlot);
        this.states = new byte[this.slotCount];
        this.chunkKeys = new long[this.slotCount];
        this.acquiredNanos = new long[this.slotCount];
        this.sectionFlatBuffers = new byte[this.slotCount][this.sectionsPerSlot][];
    }

    public static WaveChunkArena global() {
        return GLOBAL;
    }

    public int slotCount() {
        return this.slotCount;
    }

    public int sectionsPerSlot() {
        return this.sectionsPerSlot;
    }

    public int activeSlots() {
        return this.activeSlots.get();
    }

    public double occupancyRatio() {
        return (double) this.activeSlots.get() / (double) this.slotCount;
    }

    public boolean shouldDetachLongLived() {
        return this.activeSlots.get() >= ((this.slotCount * 3) >>> 2);
    }

    public byte stateOf(final int slotId) {
        if (slotId < 0 || slotId >= this.slotCount) {
            return STATE_FREE;
        }
        return (byte) BYTE_ARRAY_VH.getAcquire(this.states, slotId);
    }

    public long chunkKeyOf(final int slotId) {
        if (slotId < 0 || slotId >= this.slotCount) {
            return Long.MIN_VALUE;
        }
        return this.chunkKeys[slotId];
    }

    public long acquiredNanosOf(final int slotId) {
        if (slotId < 0 || slotId >= this.slotCount) {
            return 0L;
        }
        return this.acquiredNanos[slotId];
    }

    /**
     * Acquires a free wave slot for GPU generation by advancing the ring cursor and CASing
     * {@code STATE_FREE (0) -> STATE_GPU_GEN (1)}. Returns {@code -1} if the arena is saturated
     * so the caller falls back cleanly to normal heap allocation.
     */
    public int acquireForGpuGen(final long chunkKey) {
        final int start = this.allocCursor.getAndIncrement() & this.slotMask;
        final int scanLimit = Math.min(this.slotCount, 128);
        for (int probe = 0; probe < scanLimit; probe++) {
            final int slot = (start + probe) & this.slotMask;
            if (BYTE_ARRAY_VH.compareAndSet(this.states, slot, STATE_FREE, STATE_GPU_GEN)) {
                this.chunkKeys[slot] = chunkKey;
                this.acquiredNanos[slot] = System.nanoTime();
                this.chunkKeyToSlot.put(chunkKey, slot);
                this.activeSlots.incrementAndGet();
                this.totalAcquires.incrementAndGet();
                return slot;
            }
        }
        this.exhaustionFallbacks.incrementAndGet();
        return -1;
    }

    /**
     * Returns the reusable {@code 2,048 B} 4-bit Mode A {@code FlatBitPack} section buffer for
     * {@code (slotId, sectionIdx)}, lazily initializing it once per slot and reusing it across
     * all subsequent waves with zero heap allocation.
     */
    public byte[] sectionFlatBuffer(final int slotId, final int sectionIdx) {
        if (slotId < 0 || slotId >= this.slotCount || sectionIdx < 0 || sectionIdx >= this.sectionsPerSlot) {
            return new byte[FLAT_4BIT_SECTION_BYTES];
        }
        byte[] buf = this.sectionFlatBuffers[slotId][sectionIdx];
        if (buf == null) {
            buf = new byte[FLAT_4BIT_SECTION_BYTES];
            this.sectionFlatBuffers[slotId][sectionIdx] = buf;
        }
        return buf;
    }

    /**
     * Transitions {@code STATE_GPU_GEN (1) -> STATE_LIVE (2)} once GPU writeback attaches to the chunk.
     */
    public boolean markLive(final int slotId) {
        if (slotId < 0 || slotId >= this.slotCount) {
            return false;
        }
        return BYTE_ARRAY_VH.compareAndSet(this.states, slotId, STATE_GPU_GEN, STATE_LIVE);
    }

    /**
     * Transitions {@code STATE_LIVE (2) -> STATE_SAVE_IN_FLIGHT (3)} before handing the slot to
     * Mode A disk serialization / NVMe {@code pwrite}.
     */
    public boolean tryBeginSave(final int slotId) {
        if (slotId < 0 || slotId >= this.slotCount) {
            return false;
        }
        return BYTE_ARRAY_VH.compareAndSet(this.states, slotId, STATE_LIVE, STATE_SAVE_IN_FLIGHT);
    }

    /**
     * Marks the chunk unloaded from the world:
     * <ul>
     *   <li>{@code STATE_LIVE (2) -> STATE_FREE (0)} immediately</li>
     *   <li>{@code STATE_GPU_GEN (1) -> STATE_FREE (0)} immediately</li>
     *   <li>{@code STATE_SAVE_IN_FLIGHT (3) -> STATE_UNLOADED_SAVING (4)} so the slot remains
     *       pinned until {@link #completeSave(int)} finishes I/O (ABA-safe under {@code S-002})</li>
     * </ul>
     */
    public boolean markUnloaded(final int slotId) {
        if (slotId < 0 || slotId >= this.slotCount) {
            return false;
        }
        while (true) {
            final byte cur = (byte) BYTE_ARRAY_VH.getAcquire(this.states, slotId);
            if (cur == STATE_FREE || cur == STATE_UNLOADED_SAVING) {
                return false;
            }
            if (cur == STATE_LIVE || cur == STATE_GPU_GEN) {
                if (BYTE_ARRAY_VH.compareAndSet(this.states, slotId, cur, STATE_FREE)) {
                    this.chunkKeyToSlot.remove(this.chunkKeys[slotId], slotId);
                    this.activeSlots.decrementAndGet();
                    return true;
                }
                continue;
            }
            if (cur == STATE_SAVE_IN_FLIGHT) {
                if (BYTE_ARRAY_VH.compareAndSet(this.states, slotId, STATE_SAVE_IN_FLIGHT, STATE_UNLOADED_SAVING)) {
                    this.deferredUnloadReleases.incrementAndGet();
                    return true;
                }
            }
        }
    }

    /**
     * Completes an in-flight Mode A save:
     * <ul>
     *   <li>{@code STATE_SAVE_IN_FLIGHT (3) -> STATE_LIVE (2)} if the chunk is still loaded</li>
     *   <li>{@code STATE_UNLOADED_SAVING (4) -> STATE_FREE (0)} if the chunk unloaded while I/O
     *       was running, returning the slot to the next allocation wave</li>
     * </ul>
     */
    public boolean completeSave(final int slotId) {
        if (slotId < 0 || slotId >= this.slotCount) {
            return false;
        }
        while (true) {
            final byte cur = (byte) BYTE_ARRAY_VH.getAcquire(this.states, slotId);
            if (cur == STATE_SAVE_IN_FLIGHT) {
                if (BYTE_ARRAY_VH.compareAndSet(this.states, slotId, STATE_SAVE_IN_FLIGHT, STATE_LIVE)) {
                    return true;
                }
                continue;
            }
            if (cur == STATE_UNLOADED_SAVING) {
                if (BYTE_ARRAY_VH.compareAndSet(this.states, slotId, STATE_UNLOADED_SAVING, STATE_FREE)) {
                    this.chunkKeyToSlot.remove(this.chunkKeys[slotId], slotId);
                    this.activeSlots.decrementAndGet();
                    return true;
                }
                continue;
            }
            return false;
        }
    }

    /**
     * Looks up any wave slot currently bound to {@code chunkKey} and marks it unloaded.
     */
    public boolean markUnloadedByChunkKey(final long chunkKey) {
        final Integer slot = this.chunkKeyToSlot.get(chunkKey);
        if (slot == null) {
            return false;
        }
        return markUnloaded(slot);
    }

    public long totalAcquires() {
        return this.totalAcquires.get();
    }

    public long deferredUnloadReleases() {
        return this.deferredUnloadReleases.get();
    }

    public long exhaustionFallbacks() {
        return this.exhaustionFallbacks.get();
    }
}
