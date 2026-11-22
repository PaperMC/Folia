package dev.leafcraft.goldleaf.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldgenWaveCoalescerTest {

    @Test
    void dispatchesImmediatelyWhenMaxWaveSizeReached() throws Exception {
        final List<Integer> observedBatchSizes = new CopyOnWriteArrayList<>();
        try (WorldgenWaveCoalescer<Integer, Integer> coalescer = new WorldgenWaveCoalescer<>(
            4,
            TimeUnit.SECONDS.toNanos(5L),
            (key, batch) -> {
                observedBatchSizes.add(batch.size());
                final List<Integer> out = new ArrayList<>(batch.size());
                for (final Integer value : batch) {
                    out.add(value * 10);
                }
                return out;
            },
            Runnable::run
        )) {
            final WorldgenWaveCoalescer.WaveKey key = new WorldgenWaveCoalescer.WaveKey("minecraft:overworld", 42);
            final List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                futures.add(coalescer.submit(key, i));
            }
            for (int i = 0; i < 4; i++) {
                assertEquals((i + 1) * 10, futures.get(i).get(2, TimeUnit.SECONDS));
            }
            assertEquals(List.of(4), observedBatchSizes);
        }
    }

    @Test
    void flushesSingleChunkWithinLowLatencyDeadline() throws Exception {
        final List<Integer> observedBatchSizes = new CopyOnWriteArrayList<>();
        try (WorldgenWaveCoalescer<String, String> coalescer = new WorldgenWaveCoalescer<>(
            24,
            TimeUnit.MILLISECONDS.toNanos(2L),
            (key, batch) -> {
                observedBatchSizes.add(batch.size());
                return batch;
            },
            Runnable::run
        )) {
            final WorldgenWaveCoalescer.WaveKey key = new WorldgenWaveCoalescer.WaveKey("minecraft:overworld", 7);
            final CompletableFuture<String> future = coalescer.submit(key, "chunk-0-0");
            assertEquals("chunk-0-0", future.get(2, TimeUnit.SECONDS));
            assertEquals(List.of(1), observedBatchSizes);
        }
    }

    @Test
    void doesNotHoldBucketLockDuringBatchDispatch() throws Exception {
        final CountDownLatch insideDispatch = new CountDownLatch(1);
        final CountDownLatch releaseDispatch = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try (WorldgenWaveCoalescer<Integer, Integer> coalescer = new WorldgenWaveCoalescer<>(
            1,
            TimeUnit.MILLISECONDS.toNanos(2L),
            (key, batch) -> {
                insideDispatch.countDown();
                try {
                    releaseDispatch.await(5, TimeUnit.SECONDS);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return batch;
            },
            pool
        )) {
            final WorldgenWaveCoalescer.WaveKey key = new WorldgenWaveCoalescer.WaveKey("minecraft:overworld", 99);
            final CompletableFuture<Integer> first = coalescer.submit(key, 1);
            assertTrue(insideDispatch.await(2, TimeUnit.SECONDS));

            // Second submit for the SAME WaveKey must not block while first batch is dispatching
            final CompletableFuture<Integer> second = coalescer.submit(key, 2);
            releaseDispatch.countDown();

            assertEquals(1, first.get(2, TimeUnit.SECONDS));
            assertEquals(2, second.get(2, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void propagatesBatchFailuresToAllFuturesAndClosesIdempotently() {
        final WorldgenWaveCoalescer<Integer, Integer> coalescer = new WorldgenWaveCoalescer<>(
            2,
            TimeUnit.SECONDS.toNanos(5L),
            (key, batch) -> {
                throw new IllegalStateException("synthetic GPU failure");
            },
            Runnable::run
        );
        final WorldgenWaveCoalescer.WaveKey key = new WorldgenWaveCoalescer.WaveKey("minecraft:the_nether", 5);
        final CompletableFuture<Integer> f1 = coalescer.submit(key, 1);
        final CompletableFuture<Integer> f2 = coalescer.submit(key, 2);

        assertThrows(ExecutionException.class, () -> f1.get(2, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> f2.get(2, TimeUnit.SECONDS));

        coalescer.close();
        coalescer.close(); // idempotent (S-002)
        assertTrue(coalescer.isClosed());

        final CompletableFuture<Integer> rejected = coalescer.submit(key, 3);
        assertTrue(rejected.isCompletedExceptionally());
    }
}
