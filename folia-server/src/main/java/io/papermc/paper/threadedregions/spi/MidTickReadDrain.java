package io.papermc.paper.threadedregions.spi;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 upstream-candidate strictly read-only, micro-budgeted mid-tick safe-point query drain.
 *
 * <p>Supports atomic migration across region {@code merge}/{@code split} transitions and
 * fail-closed cancellation of pending futures when a {@code RegionizedWorldData} shell is reset
 * for pooling.</p>
 */
public class MidTickReadDrain {

    private static final Logger LOGGER = LoggerFactory.getLogger(MidTickReadDrain.class);

    public static final long DEFAULT_BUDGET_NANOS = 250_000L;
    public static final long MAX_BUDGET_NANOS = 500_000L;

    /**
     * A queued read-only entry with optional chunk coordinates for region split routing and an
     * optional cancellation callback so pooled/closed drains never hang awaiting futures.
     */
    public static final class Entry {
        private final Runnable task;
        private final @Nullable Runnable cancelCallback;
        private final boolean hasChunkCoords;
        private final int chunkX;
        private final int chunkZ;

        Entry(
            final Runnable task,
            final @Nullable Runnable cancelCallback,
            final boolean hasChunkCoords,
            final int chunkX,
            final int chunkZ
        ) {
            this.task = task;
            this.cancelCallback = cancelCallback;
            this.hasChunkCoords = hasChunkCoords;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        public boolean hasChunkCoords() {
            return this.hasChunkCoords;
        }

        public int chunkX() {
            return this.chunkX;
        }

        public int chunkZ() {
            return this.chunkZ;
        }

        void run() {
            this.task.run();
        }

        void cancel() {
            if (this.cancelCallback != null) {
                this.cancelCallback.run();
            }
        }
    }

    private final Object lifecycleLock = new Object();
    private final ConcurrentLinkedQueue<Entry> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private volatile boolean closed = false;
    private volatile @Nullable MidTickReadDrain redirectTarget = null;

    public void enqueue(final Runnable readOnlyTask) {
        Objects.requireNonNull(readOnlyTask, "readOnlyTask");
        this.enqueueEntry(new Entry(readOnlyTask, null, false, 0, 0));
    }

    public void enqueue(final int chunkX, final int chunkZ, final Runnable readOnlyTask) {
        Objects.requireNonNull(readOnlyTask, "readOnlyTask");
        this.enqueueEntry(new Entry(readOnlyTask, null, true, chunkX, chunkZ));
    }

    public <T> CompletableFuture<T> submitRead(final Supplier<T> readOnlyQuery) {
        Objects.requireNonNull(readOnlyQuery, "readOnlyQuery");
        return this.submitReadInternal(false, 0, 0, readOnlyQuery);
    }

    public <T> CompletableFuture<T> submitRead(
        final int chunkX,
        final int chunkZ,
        final Supplier<T> readOnlyQuery
    ) {
        Objects.requireNonNull(readOnlyQuery, "readOnlyQuery");
        return this.submitReadInternal(true, chunkX, chunkZ, readOnlyQuery);
    }

    private <T> CompletableFuture<T> submitReadInternal(
        final boolean hasChunkCoords,
        final int chunkX,
        final int chunkZ,
        final Supplier<T> readOnlyQuery
    ) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        final Runnable task = () -> {
            if (future.isDone()) {
                return;
            }
            try {
                future.complete(readOnlyQuery.get());
            } catch (final Throwable t) {
                LOGGER.warn("Mid-tick read-only query failed", t);
                future.completeExceptionally(t);
            }
        };
        final Runnable cancel = () -> future.completeExceptionally(
            new CancellationException("MidTickReadDrain closed before read-only query could execute")
        );
        this.enqueueEntry(new Entry(task, cancel, hasChunkCoords, chunkX, chunkZ));
        return future;
    }

    private void enqueueEntry(final Entry entry) {
        final MidTickReadDrain redirect = this.redirectTarget;
        if (redirect != null && redirect != this) {
            redirect.enqueueEntry(entry);
            return;
        }
        if (this.closed) {
            entry.cancel();
            return;
        }
        synchronized (this.lifecycleLock) {
            final MidTickReadDrain lockedRedirect = this.redirectTarget;
            if (lockedRedirect != null && lockedRedirect != this) {
                lockedRedirect.enqueueEntry(entry);
                return;
            }
            if (this.closed) {
                entry.cancel();
                return;
            }
            this.queue.add(entry);
            this.pendingCount.incrementAndGet();
        }
    }

    public boolean isEmpty() {
        return this.pendingCount.get() <= 0;
    }

    public int pendingCount() {
        return Math.max(0, this.pendingCount.get());
    }

    public int drain() {
        return this.drain(DEFAULT_BUDGET_NANOS);
    }

    public int drain(final long budgetNanos) {
        if (this.pendingCount.get() <= 0) {
            return 0;
        }
        final long clampedBudget = Math.max(1L, Math.min(budgetNanos, MAX_BUDGET_NANOS));
        final long deadline = System.nanoTime() + clampedBudget;
        int executed = 0;

        while (true) {
            final Entry entry = this.queue.poll();
            if (entry == null) {
                break;
            }
            this.pendingCount.decrementAndGet();
            try {
                entry.run();
            } catch (final Throwable t) {
                LOGGER.warn("Uncaught exception in MidTickReadDrain task", t);
            }
            executed++;
            if (System.nanoTime() - deadline >= 0L) {
                break;
            }
        }

        return executed;
    }

    /**
     * Atomically migrates all pending entries into {@code target} during a region merge,
     * redirecting any concurrent off-thread enqueues to {@code target}.
     */
    public int migrateAllTo(final MidTickReadDrain target) {
        Objects.requireNonNull(target, "target");
        if (target == this) {
            return 0;
        }
        int migrated = 0;
        synchronized (this.lifecycleLock) {
            this.redirectTarget = target;
            Entry entry;
            while ((entry = this.queue.poll()) != null) {
                this.pendingCount.decrementAndGet();
                target.enqueueEntry(entry);
                migrated++;
            }
            this.pendingCount.set(0);
        }
        return migrated;
    }

    /**
     * Atomically migrates pending entries across split target regions using {@code targetRouter}.
     * Entries whose router returns {@code null} (unpinned or unmapped) fail closed via cancellation.
     */
    public int migrateSplit(final Function<Entry, @Nullable MidTickReadDrain> targetRouter) {
        Objects.requireNonNull(targetRouter, "targetRouter");
        int migrated = 0;
        synchronized (this.lifecycleLock) {
            this.closed = true;
            Entry entry;
            while ((entry = this.queue.poll()) != null) {
                this.pendingCount.decrementAndGet();
                final MidTickReadDrain target = targetRouter.apply(entry);
                if (target != null && target != this) {
                    target.enqueueEntry(entry);
                    migrated++;
                } else {
                    entry.cancel();
                }
            }
            this.pendingCount.set(0);
        }
        return migrated;
    }

    /**
     * Closes the drain for shell pooling and fails closed any remaining pending futures with
     * {@link CancellationException} so callers never hang indefinitely.
     */
    public int cancelAll() {
        int cancelled = 0;
        synchronized (this.lifecycleLock) {
            this.closed = true;
            this.redirectTarget = null;
            Entry entry;
            while ((entry = this.queue.poll()) != null) {
                this.pendingCount.decrementAndGet();
                entry.cancel();
                cancelled++;
            }
            this.pendingCount.set(0);
        }
        return cancelled;
    }

    /**
     * Reopens a pooled drain when its owning {@code RegionizedWorldData} shell is rebound to a new region.
     */
    public void reopen() {
        synchronized (this.lifecycleLock) {
            this.redirectTarget = null;
            this.closed = false;
        }
    }

    public void clear() {
        this.cancelAll();
    }
}
