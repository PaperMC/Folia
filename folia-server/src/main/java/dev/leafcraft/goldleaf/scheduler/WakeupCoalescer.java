package dev.leafcraft.goldleaf.scheduler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Lock-free {@code 0 -> 1} wakeup coalescer with a wakeup-loss-free drain-loop protocol
 * ({@code goldleaf-ADR-002} Layer 1).
 *
 * <p>Prevents redundant scheduler wakeup storms when many intermediate tasks are enqueued
 * concurrently while guaranteeing that concurrent arrivals during a drain never stall until
 * the next tick boundary.</p>
 */
public final class WakeupCoalescer {

    private final AtomicBoolean notified = new AtomicBoolean(false);
    private final AtomicLong signalsAccepted = new AtomicLong();
    private final AtomicLong signalsCoalesced = new AtomicLong();
    private final AtomicLong rearms = new AtomicLong();

    /**
     * Attempts a {@code 0 -> 1} wakeup transition.
     *
     * @return {@code true} if the caller won the transition and must notify the scheduler;
     *         {@code false} if a wakeup is already pending.
     */
    public boolean signal() {
        if (this.notified.compareAndSet(false, true)) {
            this.signalsAccepted.incrementAndGet();
            return true;
        }
        this.signalsCoalesced.incrementAndGet();
        return false;
    }

    /**
     * Convenience helper that invokes {@code notifyCallback} only on a {@code 0 -> 1} transition.
     */
    public boolean signalAndNotify(final Runnable notifyCallback) {
        Objects.requireNonNull(notifyCallback, "notifyCallback");
        if (this.signal()) {
            notifyCallback.run();
            return true;
        }
        return false;
    }

    /**
     * Executes {@code drainStep} using the Dekker-ordered clear-before-drain / re-arm-if-nonempty
     * protocol so concurrent producers can never lose a wakeup.
     */
    public void drain(
        final BooleanSupplier hasMoreWork,
        final Runnable drainStep,
        final Runnable notifyCallback
    ) {
        Objects.requireNonNull(hasMoreWork, "hasMoreWork");
        Objects.requireNonNull(drainStep, "drainStep");
        Objects.requireNonNull(notifyCallback, "notifyCallback");

        // 1. Clear pending notification before draining so any producer arriving after this
        //    point either has its item drained below or observes notified == false and wakes us.
        this.notified.set(false);

        // 2. Drain available work.
        try {
            drainStep.run();
        } finally {
            // 3. If work remains (e.g., time slice expired or arrived during drain), re-arm
            //    and re-notify on the 0 -> 1 transition.
            if (hasMoreWork.getAsBoolean() && this.notified.compareAndSet(false, true)) {
                this.rearms.incrementAndGet();
                notifyCallback.run();
            }
        }
    }

    public boolean isNotified() {
        return this.notified.get();
    }

    public long getSignalsAccepted() {
        return this.signalsAccepted.get();
    }

    public long getSignalsCoalesced() {
        return this.signalsCoalesced.get();
    }

    public long getRearms() {
        return this.rearms.get();
    }
}
