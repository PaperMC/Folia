package dev.leafcraft.goldleaf.scheduler;

import io.papermc.paper.threadedregions.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubTickInterruptSchedulerTest {

    @Test
    void wakeupCoalescerDeliversAllItemsUnderConcurrentProducerStressWithoutLostWakeups() throws Exception {
        final int producerCount = 8;
        final int itemsPerProducer = 5_000;
        final int totalItems = producerCount * itemsPerProducer;

        final WakeupCoalescer coalescer = new WakeupCoalescer();
        final ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger consumed = new AtomicInteger();
        final CountDownLatch allConsumed = new CountDownLatch(1);
        final AtomicReference<Thread> consumerRef = new AtomicReference<>();

        final Thread consumer = new Thread(() -> {
            while (consumed.get() < totalItems && !Thread.currentThread().isInterrupted()) {
                if (!coalescer.isNotified() && queue.isEmpty()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50L));
                }
                coalescer.drain(
                    () -> !queue.isEmpty(),
                    () -> {
                        int batch = 0;
                        while (batch < 64 && queue.poll() != null) {
                            batch++;
                            if (consumed.incrementAndGet() == totalItems) {
                                allConsumed.countDown();
                            }
                        }
                    },
                    () -> LockSupport.unpark(consumerRef.get())
                );
            }
        }, "goldleaf-coalescer-consumer");
        consumerRef.set(consumer);
        consumer.start();

        final ExecutorService producers = Executors.newFixedThreadPool(producerCount);
        final CountDownLatch startGate = new CountDownLatch(1);
        try {
            for (int p = 0; p < producerCount; p++) {
                producers.submit(() -> {
                    try {
                        startGate.await();
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < itemsPerProducer; i++) {
                        queue.add(i);
                        coalescer.signalAndNotify(() -> LockSupport.unpark(consumer));
                    }
                });
            }
            startGate.countDown();

            assertTrue(allConsumed.await(15, TimeUnit.SECONDS),
                "Consumer stalled at " + consumed.get() + "/" + totalItems + " items");
            assertEquals(totalItems, consumed.get());
            assertTrue(coalescer.getSignalsCoalesced() > 0L, "Expected wakeup coalescing under concurrent load");
        } finally {
            producers.shutdownNow();
            consumer.interrupt();
            consumer.join(2_000L);
        }
    }

    @Test
    void wakeupCoalescerRearmsWhenDrainSliceLeavesRemainingWork() {
        final WakeupCoalescer coalescer = new WakeupCoalescer();
        final AtomicInteger notifications = new AtomicInteger();
        final AtomicInteger remaining = new AtomicInteger(3);

        assertTrue(coalescer.signalAndNotify(notifications::incrementAndGet));
        assertFalse(coalescer.signalAndNotify(notifications::incrementAndGet));
        assertEquals(1, notifications.get());

        // Drain only 1 item per slice; coalescer must re-arm and notify until empty
        coalescer.drain(() -> remaining.get() > 0, remaining::decrementAndGet, notifications::incrementAndGet);
        assertEquals(2, remaining.get());
        assertEquals(2, notifications.get());
        assertEquals(1L, coalescer.getRearms());
        assertTrue(coalescer.isNotified());

        // Drain the rest
        coalescer.drain(() -> remaining.get() > 0, () -> remaining.set(0), notifications::incrementAndGet);
        assertEquals(0, remaining.get());
        assertEquals(2, notifications.get());
        assertFalse(coalescer.isNotified());
    }

    @Test
    void entitySchedulerImmediateTasksHonorRetirementContract() {
        final CraftEntity craftEntity = Mockito.mock(CraftEntity.class);
        Mockito.when(craftEntity.getHandleRaw()).thenReturn(null);
        final EntityScheduler scheduler = new EntityScheduler(craftEntity);

        final AtomicInteger runCount = new AtomicInteger();
        final AtomicInteger retireCount = new AtomicInteger();

        assertTrue(scheduler.scheduleImmediate(e -> runCount.incrementAndGet(), e -> retireCount.incrementAndGet()));
        assertTrue(scheduler.scheduleImmediate(e -> runCount.incrementAndGet(), e -> retireCount.incrementAndGet()));

        scheduler.retire();
        assertEquals(0, runCount.get());
        assertEquals(2, retireCount.get());

        // Scheduling after retirement must return false and invoke neither callback
        assertFalse(scheduler.scheduleImmediate(e -> runCount.incrementAndGet(), e -> retireCount.incrementAndGet()));
        assertEquals(0, runCount.get());
        assertEquals(2, retireCount.get());
    }

    @Test
    void publicSchedulerApiDefaultsFallbackCleanly() {
        final Plugin plugin = Mockito.mock(Plugin.class);
        final Runnable task = () -> {};

        final io.papermc.paper.threadedregions.scheduler.EntityScheduler apiEntityScheduler =
            Mockito.mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class, Mockito.CALLS_REAL_METHODS);
        Mockito.when(apiEntityScheduler.execute(plugin, task, null, 1L)).thenReturn(true);
        assertTrue(apiEntityScheduler.executeImmediate(plugin, task, null));
        Mockito.verify(apiEntityScheduler).execute(plugin, task, null, 1L);

        final GlobalRegionScheduler apiGlobalScheduler =
            Mockito.mock(GlobalRegionScheduler.class, Mockito.CALLS_REAL_METHODS);
        apiGlobalScheduler.executeImmediate(plugin, task);
        Mockito.verify(apiGlobalScheduler).execute(plugin, task);
    }
}
