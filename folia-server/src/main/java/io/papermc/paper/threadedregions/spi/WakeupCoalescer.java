package io.papermc.paper.threadedregions.spi;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Layer 1 upstream-candidate lock-free {@code 0 -> 1} wakeup coalescer with a wakeup-loss-free
 * drain-loop protocol.
 */
public class WakeupCoalescer {

    private final AtomicBoolean notified = new AtomicBoolean(false);
    private final AtomicLong signalsAccepted = new AtomicLong();
    private final AtomicLong signalsCoalesced = new AtomicLong();
    private final AtomicLong rearms = new AtomicLong();

    public boolean signal() {
        if (this.notified.compareAndSet(false, true)) {
            this.signalsAccepted.incrementAndGet();
            return true;
        }
        this.signalsCoalesced.incrementAndGet();
        return false;
    }

    public boolean signalAndNotify(final Runnable notifyCallback) {
        Objects.requireNonNull(notifyCallback, "notifyCallback");
        if (this.signal()) {
            notifyCallback.run();
            return true;
        }
        return false;
    }

    public void drain(
        final BooleanSupplier hasMoreWork,
        final Runnable drainStep,
        final Runnable notifyCallback
    ) {
        Objects.requireNonNull(hasMoreWork, "hasMoreWork");
        Objects.requireNonNull(drainStep, "drainStep");
        Objects.requireNonNull(notifyCallback, "notifyCallback");

        this.notified.set(false);
        try {
            drainStep.run();
        } finally {
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
