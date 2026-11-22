package dev.leafcraft.goldleaf.worldgen;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded wave coalescer for {@code BIOMES} and {@code TERRAIN} generation tasks
 * ({@code goldleaf-ADR-004} §2.1).
 *
 * <p>Coalesces ready generation tasks sharing a {@link WaveKey} ({@code (levelKey, randomStateId)})
 * into bounded waves ({@code 16..32} chunks) with a strict low-latency flush deadline
 * ({@code 1..2 ms}) so single-player exploration never waits on a full batch while pre-gen and
 * teleport bursts saturate GPU/SIMD accelerators.</p>
 *
 * <p><b>Lock-free dispatch discipline:</b> Per-key monitors are held solely for O(1) append and
 * atomic batch detachment; neither the {@code 1..2 ms} flush wait nor the downstream batch
 * dispatcher ever executes while holding a bucket lock. {@link #close()} is idempotent
 * ({@code S-002}) and flushes any remaining pending waves before returning.</p>
 *
 * @param <T> task payload type (e.g., {@link TerrainGenerationAccelerator.BiomeTask} or
 *            {@link TerrainGenerationAccelerator.TerrainTask})
 * @param <R> per-task completion result type
 */
public final class WorldgenWaveCoalescer<T, R> implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldgenWaveCoalescer.class);

    public static final int DEFAULT_MAX_WAVE_SIZE = 24;
    public static final long DEFAULT_FLUSH_DEADLINE_NANOS = TimeUnit.MILLISECONDS.toNanos(2L);

    public record WaveKey(String levelId, int randomStateIdentity) {
        public WaveKey {
            Objects.requireNonNull(levelId, "levelId");
        }
    }

    private record PendingItem<T, R>(T task, CompletableFuture<R> future) {
    }

    private final class WaveBucket {
        private List<PendingItem<T, R>> items = new ArrayList<>();
        private @Nullable ScheduledFuture<?> flushHandle;
        private long generation;

        synchronized @Nullable List<PendingItem<T, R>> appendOrDetach(
            final WaveKey key,
            final PendingItem<T, R> item
        ) {
            this.items.add(item);
            if (this.items.size() >= WorldgenWaveCoalescer.this.maxWaveSize) {
                return this.detachLocked();
            }
            if (this.items.size() == 1 && this.flushHandle == null) {
                final long expectedGeneration = this.generation;
                this.flushHandle = WorldgenWaveCoalescer.this.flushScheduler.schedule(
                    () -> WorldgenWaveCoalescer.this.flushBucketIfGenerationMatches(key, this, expectedGeneration),
                    WorldgenWaveCoalescer.this.flushDeadlineNanos,
                    TimeUnit.NANOSECONDS
                );
            }
            return null;
        }

        synchronized @Nullable List<PendingItem<T, R>> detachIfGenerationMatches(final long expectedGeneration) {
            if (this.generation != expectedGeneration || this.items.isEmpty()) {
                return null;
            }
            return this.detachLocked();
        }

        synchronized @Nullable List<PendingItem<T, R>> detachAll() {
            if (this.items.isEmpty()) {
                return null;
            }
            return this.detachLocked();
        }

        private List<PendingItem<T, R>> detachLocked() {
            final List<PendingItem<T, R>> detached = this.items;
            this.items = new ArrayList<>();
            this.generation++;
            if (this.flushHandle != null) {
                this.flushHandle.cancel(false);
                this.flushHandle = null;
            }
            return detached;
        }
    }

    private final int maxWaveSize;
    private final long flushDeadlineNanos;
    private final BiFunction<WaveKey, List<T>, List<R>> batchDispatcher;
    private final Executor dispatchExecutor;
    private final ScheduledExecutorService flushScheduler;
    private final boolean ownsFlushScheduler;
    private final ConcurrentHashMap<WaveKey, WaveBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public WorldgenWaveCoalescer(
        final int maxWaveSize,
        final long flushDeadlineNanos,
        final BiFunction<WaveKey, List<T>, List<R>> batchDispatcher,
        final Executor dispatchExecutor
    ) {
        this(maxWaveSize, flushDeadlineNanos, batchDispatcher, dispatchExecutor, createDefaultFlushScheduler(), true);
    }

    public WorldgenWaveCoalescer(
        final int maxWaveSize,
        final long flushDeadlineNanos,
        final BiFunction<WaveKey, List<T>, List<R>> batchDispatcher,
        final Executor dispatchExecutor,
        final ScheduledExecutorService flushScheduler
    ) {
        this(maxWaveSize, flushDeadlineNanos, batchDispatcher, dispatchExecutor, flushScheduler, false);
    }

    private WorldgenWaveCoalescer(
        final int maxWaveSize,
        final long flushDeadlineNanos,
        final BiFunction<WaveKey, List<T>, List<R>> batchDispatcher,
        final Executor dispatchExecutor,
        final ScheduledExecutorService flushScheduler,
        final boolean ownsFlushScheduler
    ) {
        if (maxWaveSize < 1 || maxWaveSize > 64) {
            throw new IllegalArgumentException("maxWaveSize must be in [1, 64], got " + maxWaveSize);
        }
        if (flushDeadlineNanos < 1L) {
            throw new IllegalArgumentException("flushDeadlineNanos must be >= 1, got " + flushDeadlineNanos);
        }
        this.maxWaveSize = maxWaveSize;
        this.flushDeadlineNanos = flushDeadlineNanos;
        this.batchDispatcher = Objects.requireNonNull(batchDispatcher, "batchDispatcher");
        this.dispatchExecutor = Objects.requireNonNull(dispatchExecutor, "dispatchExecutor");
        this.flushScheduler = Objects.requireNonNull(flushScheduler, "flushScheduler");
        this.ownsFlushScheduler = ownsFlushScheduler;
    }

    private static ScheduledExecutorService createDefaultFlushScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "GoldLeaf-Worldgen-Wave-Flusher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public int maxWaveSize() {
        return this.maxWaveSize;
    }

    public long flushDeadlineNanos() {
        return this.flushDeadlineNanos;
    }

    public boolean isClosed() {
        return this.closed.get();
    }

    /**
     * Submits a generation task to the wave coalescer. Returns a future completed when its wave
     * finishes dispatching. Rejects new submissions once {@link #close()} has been called.
     */
    public CompletableFuture<R> submit(final WaveKey key, final T task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        if (this.closed.get()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("WorldgenWaveCoalescer is closed")
            );
        }

        final CompletableFuture<R> future = new CompletableFuture<>();
        final PendingItem<T, R> item = new PendingItem<>(task, future);
        final WaveBucket bucket = this.buckets.computeIfAbsent(key, ignored -> new WaveBucket());
        final List<PendingItem<T, R>> readyBatch = bucket.appendOrDetach(key, item);
        if (readyBatch != null) {
            this.dispatchDetached(key, readyBatch);
        }
        return future;
    }

    /**
     * Forces an immediate flush of all currently buffered waves across all keys.
     */
    public void flushAll() {
        this.buckets.forEach((key, bucket) -> {
            final List<PendingItem<T, R>> detached = bucket.detachAll();
            if (detached != null) {
                this.dispatchDetached(key, detached);
            }
        });
    }

    private void flushBucketIfGenerationMatches(
        final WaveKey key,
        final WaveBucket bucket,
        final long expectedGeneration
    ) {
        final List<PendingItem<T, R>> detached = bucket.detachIfGenerationMatches(expectedGeneration);
        if (detached != null) {
            this.dispatchDetached(key, detached);
        }
    }

    private void dispatchDetached(final WaveKey key, final List<PendingItem<T, R>> batch) {
        final List<T> tasks = new ArrayList<>(batch.size());
        for (final PendingItem<T, R> item : batch) {
            tasks.add(item.task());
        }
        final List<T> immutableTasks = List.copyOf(tasks);
        try {
            this.dispatchExecutor.execute(() -> {
                try {
                    final List<R> results = this.batchDispatcher.apply(key, immutableTasks);
                    if (results == null || results.size() != batch.size()) {
                        final IllegalStateException mismatch = new IllegalStateException(
                            "WorldgenWaveCoalescer batchDispatcher returned "
                                + (results == null ? "null" : results.size())
                                + " results for batch size " + batch.size()
                        );
                        LOGGER.warn("GoldLeaf worldgen wave dispatch result count mismatch for {}", key, mismatch);
                        for (final PendingItem<T, R> item : batch) {
                            item.future().completeExceptionally(mismatch);
                        }
                        return;
                    }
                    for (int i = 0; i < batch.size(); i++) {
                        batch.get(i).future().complete(results.get(i));
                    }
                } catch (final Throwable t) {
                    // Propagate failure to every task future without silent swallow (S-004)
                    LOGGER.warn("GoldLeaf worldgen wave dispatch failed for {} ({} chunks)", key, batch.size(), t);
                    for (final PendingItem<T, R> item : batch) {
                        item.future().completeExceptionally(t);
                    }
                }
            });
        } catch (final Throwable submitError) {
            LOGGER.warn("GoldLeaf worldgen wave executor rejected dispatch for {}", key, submitError);
            for (final PendingItem<T, R> item : batch) {
                item.future().completeExceptionally(submitError);
            }
        }
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.flushAll();
        this.buckets.clear();
        if (this.ownsFlushScheduler) {
            this.flushScheduler.shutdown();
        }
    }
}
