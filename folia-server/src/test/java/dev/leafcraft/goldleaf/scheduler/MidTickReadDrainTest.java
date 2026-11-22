package dev.leafcraft.goldleaf.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MidTickReadDrainTest {

    @Test
    void drainsQueuedReadsFifoAndCompletesFutures() throws Exception {
        final MidTickReadDrain drain = new MidTickReadDrain();
        assertTrue(drain.isEmpty());

        final List<String> order = new ArrayList<>();
        final CompletableFuture<Integer> first = drain.submitRead(() -> {
            order.add("first");
            return 42;
        });
        final CompletableFuture<String> second = drain.submitRead(() -> {
            order.add("second");
            return "ok";
        });

        assertFalse(drain.isEmpty());
        assertEquals(2, drain.pendingCount());

        final int ran = drain.drain();
        assertEquals(2, ran);
        assertTrue(drain.isEmpty());
        assertEquals(List.of("first", "second"), order);
        assertEquals(42, first.get());
        assertEquals("ok", second.get());
    }

    @Test
    void failsClosedOnQueryExceptionWithoutAbortingRemainingTasks() throws Exception {
        final MidTickReadDrain drain = new MidTickReadDrain();

        final CompletableFuture<String> failing = drain.submitRead(() -> {
            throw new IllegalStateException("simulated read fault");
        });
        final CompletableFuture<String> healthy = drain.submitRead(() -> "survived");

        int ran = 0;
        while (!drain.isEmpty()) {
            ran += drain.drain(MidTickReadDrain.MAX_BUDGET_NANOS);
        }
        assertEquals(2, ran);
        assertTrue(failing.isCompletedExceptionally());
        final ExecutionException ex = assertThrows(ExecutionException.class, failing::get);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals("survived", healthy.get());
    }

    @Test
    void enforcesMicroBudgetAndDefersRemainingTasksToNextSafePoint() {
        final MidTickReadDrain drain = new MidTickReadDrain();
        final AtomicInteger counter = new AtomicInteger();

        for (int i = 0; i < 10; i++) {
            drain.enqueue(() -> {
                counter.incrementAndGet();
                final long spinUntil = System.nanoTime() + 60_000L; // 60 us per task
                while (System.nanoTime() < spinUntil) {
                    Thread.onSpinWait();
                }
            });
        }

        final int firstBatch = drain.drain(100_000L); // 100 us budget
        assertTrue(firstBatch >= 1 && firstBatch < 10, "Expected partial drain under 100us budget, ran=" + firstBatch);
        assertEquals(10 - firstBatch, drain.pendingCount());

        // Drain the remainder in subsequent safe-points
        int total = firstBatch;
        while (!drain.isEmpty()) {
            total += drain.drain(MidTickReadDrain.MAX_BUDGET_NANOS);
        }
        assertEquals(10, total);
        assertEquals(10, counter.get());
    }

    @Test
    void clearEmptiesQueueForPoolReset() {
        final MidTickReadDrain drain = new MidTickReadDrain();
        final CompletableFuture<String> pending = drain.submitRead(() -> "never");
        drain.enqueue(() -> {
            throw new AssertionError("Should never run after clear()");
        });
        assertFalse(drain.isEmpty());
        drain.clear();
        assertTrue(drain.isEmpty());
        assertEquals(0, drain.drain());
        assertTrue(pending.isCompletedExceptionally());
        assertThrows(CancellationException.class, pending::get);

        // Enqueuing while closed fails closed immediately
        final CompletableFuture<String> rejected = drain.submitRead(() -> "closed");
        assertTrue(rejected.isCompletedExceptionally());

        // Reopening after pool rebind resumes normal operation
        drain.reopen();
        final CompletableFuture<String> reopened = drain.submitRead(() -> "rebound");
        assertEquals(1, drain.drain());
        assertEquals("rebound", reopened.join());
    }

    @Test
    void migrateAllToTransfersPendingAndRedirectsConcurrentEnqueues() throws Exception {
        final MidTickReadDrain from = new MidTickReadDrain();
        final MidTickReadDrain into = new MidTickReadDrain();

        final CompletableFuture<String> before = from.submitRead(2, 3, () -> "before-merge");
        final int migrated = from.migrateAllTo(into);
        assertEquals(1, migrated);
        assertTrue(from.isEmpty());

        // Off-thread enqueue against stale `from` reference transparently redirects to `into`
        final CompletableFuture<String> redirected = from.submitRead(2, 3, () -> "after-merge");
        assertEquals(2, into.pendingCount());

        assertEquals(2, into.drain());
        assertEquals("before-merge", before.get());
        assertEquals("after-merge", redirected.get());
    }

    @Test
    void migrateSplitRoutesChunkPinnedEntriesAndCancelsUnpinned() throws Exception {
        final MidTickReadDrain from = new MidTickReadDrain();
        final MidTickReadDrain left = new MidTickReadDrain();
        final MidTickReadDrain right = new MidTickReadDrain();

        final CompletableFuture<String> leftQuery = from.submitRead(-4, 0, () -> "left");
        final CompletableFuture<String> rightQuery = from.submitRead(4, 0, () -> "right");
        final CompletableFuture<String> unpinnedQuery = from.submitRead(() -> "unpinned");

        final int migrated = from.migrateSplit(entry -> {
            if (!entry.hasChunkCoords()) {
                return null;
            }
            return entry.chunkX() < 0 ? left : right;
        });

        assertEquals(2, migrated);
        assertTrue(from.isEmpty());
        assertTrue(unpinnedQuery.isCompletedExceptionally());
        assertThrows(CancellationException.class, unpinnedQuery::get);

        assertEquals(1, left.drain());
        assertEquals(1, right.drain());
        assertEquals("left", leftQuery.get());
        assertEquals("right", rightQuery.get());
    }
}
