package dev.leafcraft.goldleaf.worldgen;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded pool of reusable off-heap {@link CompactSectionWritebackBuffer} instances with
 * reference-counted per-wave leases ({@code goldleaf-ADR-017}).
 *
 * <p>Allows the GPU wave dispatch thread to enqueue and DMA wave {@code N+1} into a fresh pooled
 * buffer while CPU worker threads concurrently execute {@link CompactSectionWritebackApplier}
 * against wave {@code N}'s buffer. When the final chunk worker calls {@link PooledWaveLease#releaseChunk()},
 * the buffer automatically returns to the pool ({@code S-002}).</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017
 * @trace S-002, S-004
 */
public final class CompactSectionWritebackBufferPool implements AutoCloseable {

    public static final int DEFAULT_MAX_POOLED_BUFFERS = 8;
    public static final int DEFAULT_INITIAL_SECTIONS = 24 * 24;

    private final int maxPooledBuffers;
    private final int defaultInitialSections;
    private final ConcurrentLinkedDeque<CompactSectionWritebackBuffer> idle = new ConcurrentLinkedDeque<>();
    private final AtomicInteger idleCount = new AtomicInteger(0);
    private final AtomicInteger inUseCount = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public CompactSectionWritebackBufferPool() {
        this(DEFAULT_MAX_POOLED_BUFFERS, DEFAULT_INITIAL_SECTIONS);
    }

    public CompactSectionWritebackBufferPool(final int maxPooledBuffers, final int defaultInitialSections) {
        if (maxPooledBuffers < 1) {
            throw new IllegalArgumentException("maxPooledBuffers must be >= 1, got " + maxPooledBuffers);
        }
        if (defaultInitialSections < 1) {
            throw new IllegalArgumentException("defaultInitialSections must be >= 1, got " + defaultInitialSections);
        }
        this.maxPooledBuffers = maxPooledBuffers;
        this.defaultInitialSections = defaultInitialSections;
    }

    /**
     * Acquires a pooled off-heap writeback buffer sized for at least {@code requiredSections}
     * sections and tracked by a {@code chunkCount}-reference lease.
     */
    public PooledWaveLease acquire(final int requiredSections, final int chunkCount) {
        if (requiredSections <= 0) {
            throw new IllegalArgumentException("requiredSections must be > 0, got " + requiredSections);
        }
        if (chunkCount <= 0) {
            throw new IllegalArgumentException("chunkCount must be > 0, got " + chunkCount);
        }
        CompactSectionWritebackBuffer buffer = this.idle.pollFirst();
        if (buffer != null) {
            this.idleCount.decrementAndGet();
            buffer.ensureCapacity(1, requiredSections);
        } else {
            buffer = new CompactSectionWritebackBuffer(Math.max(requiredSections, this.defaultInitialSections));
        }
        this.inUseCount.incrementAndGet();
        return new PooledWaveLease(this, buffer, chunkCount);
    }

    private void returnBuffer(final CompactSectionWritebackBuffer buffer) {
        this.inUseCount.decrementAndGet();
        if (this.closed.get()) {
            return;
        }
        while (true) {
            final int currentIdle = this.idleCount.get();
            if (currentIdle >= this.maxPooledBuffers) {
                return;
            }
            if (this.idleCount.compareAndSet(currentIdle, currentIdle + 1)) {
                this.idle.offerFirst(buffer);
                return;
            }
        }
    }

    public int inUseCount() {
        return this.inUseCount.get();
    }

    public int pooledCount() {
        return this.idleCount.get();
    }

    public boolean isClosed() {
        return this.closed.get();
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            while (this.idle.pollFirst() != null) {
                this.idleCount.decrementAndGet();
            }
        }
    }

    /**
     * Reference-counted lease over a {@link CompactSectionWritebackBuffer} shared across all
     * parallel {@code hostApply} chunk workers in one wave.
     */
    public static final class PooledWaveLease {
        private final CompactSectionWritebackBufferPool pool;
        private final CompactSectionWritebackBuffer buffer;
        private final AtomicInteger remainingChunks;
        private final AtomicBoolean returned = new AtomicBoolean(false);

        private PooledWaveLease(
            final CompactSectionWritebackBufferPool pool,
            final CompactSectionWritebackBuffer buffer,
            final int chunkCount
        ) {
            this.pool = Objects.requireNonNull(pool, "pool");
            this.buffer = Objects.requireNonNull(buffer, "buffer");
            this.remainingChunks = new AtomicInteger(chunkCount);
        }

        public CompactSectionWritebackBuffer buffer() {
            return this.buffer;
        }

        public int remainingChunks() {
            return Math.max(0, this.remainingChunks.get());
        }

        /**
         * Decrements the per-chunk reference count. Returns the underlying buffer to the pool
         * exactly once when the count reaches zero ({@code S-002}).
         */
        public void releaseChunk() {
            if (this.remainingChunks.decrementAndGet() <= 0) {
                releaseOnce();
            }
        }

        /**
         * Releases the entire lease immediately (e.g., when GPU dispatch fails before chunk
         * fan-out begins). Idempotent ({@code S-002}).
         */
        public void releaseAll() {
            this.remainingChunks.set(0);
            releaseOnce();
        }

        private void releaseOnce() {
            if (this.returned.compareAndSet(false, true)) {
                this.pool.returnBuffer(this.buffer);
            }
        }
    }
}
