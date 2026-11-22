package dev.leafcraft.goldleaf.bot;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Region-owned handle representing a live synthetic player / bot ({@code goldleaf-ADR-015}).
 *
 * <p>Exposes explicit synthetic identity ({@link #isSyntheticPlayer()} == {@code true}),
 * deterministic UUID, chunk-loading flag, queued region-thread actions, and an idempotent
 * lifecycle state so despawn / owner-quit cleanup never leaks chunk tickets ({@code S-002}).</p>
 */
public final class SyntheticPlayerHandle {

    public record PositionSnapshot(
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {
    }

    private final UUID syntheticUuid;
    private final String name;
    private final UUID ownerUuid;
    private final boolean chunkLoadingAllowed;
    private final AtomicReference<PositionSnapshot> position;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicLong executedActions = new AtomicLong();
    private final ConcurrentLinkedQueue<SyntheticPlayerAction> pendingActions = new ConcurrentLinkedQueue<>();

    SyntheticPlayerHandle(
        final UUID syntheticUuid,
        final String name,
        final UUID ownerUuid,
        final boolean chunkLoadingAllowed,
        final PositionSnapshot initialPosition
    ) {
        this.syntheticUuid = Objects.requireNonNull(syntheticUuid, "syntheticUuid");
        this.name = Objects.requireNonNull(name, "name");
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        this.chunkLoadingAllowed = chunkLoadingAllowed;
        this.position = new AtomicReference<>(Objects.requireNonNull(initialPosition, "initialPosition"));
    }

    public UUID uuid() {
        return this.syntheticUuid;
    }

    public String name() {
        return this.name;
    }

    public UUID ownerUuid() {
        return this.ownerUuid;
    }

    public boolean isSyntheticPlayer() {
        return true;
    }

    public boolean isChunkLoadingAllowed() {
        return this.chunkLoadingAllowed;
    }

    public boolean isActive() {
        return this.active.get();
    }

    public PositionSnapshot positionSnapshot() {
        return this.position.get();
    }

    public long executedActionCount() {
        return this.executedActions.get();
    }

    public int pendingActionCount() {
        return this.pendingActions.size();
    }

    void enqueueAction(final SyntheticPlayerAction action) {
        if (!this.active.get()) {
            throw new IllegalStateException("Synthetic player '" + this.name + "' is no longer active");
        }
        this.pendingActions.offer(Objects.requireNonNull(action, "action"));
    }

    /**
     * Drains and applies queued actions on the synthetic player's owning region thread
     * ({@code F-001}..{@code F-003}).
     */
    public int drainActionsOnRegionThread() {
        if (!this.active.get()) {
            this.pendingActions.clear();
            return 0;
        }
        int processed = 0;
        SyntheticPlayerAction action;
        while (this.active.get() && (action = this.pendingActions.poll()) != null) {
            applyAction(action);
            processed++;
            this.executedActions.incrementAndGet();
        }
        return processed;
    }

    void updatePosition(final PositionSnapshot next) {
        this.position.set(Objects.requireNonNull(next, "next"));
    }

    boolean markDespawned() {
        final boolean transitioned = this.active.compareAndSet(true, false);
        this.pendingActions.clear();
        return transitioned;
    }

    private void applyAction(final SyntheticPlayerAction action) {
        final PositionSnapshot cur = this.position.get();
        switch (action.kind()) {
            case LOOK -> this.position.set(new PositionSnapshot(
                cur.worldName(), cur.x(), cur.y(), cur.z(), action.yaw(), action.pitch()
            ));
            case MOVE -> this.position.set(new PositionSnapshot(
                cur.worldName(),
                cur.x() + action.deltaX(),
                cur.y() + action.deltaY(),
                cur.z() + action.deltaZ(),
                cur.yaw(),
                cur.pitch()
            ));
            case ATTACK, USE_ITEM, SNEAK, SPRINT, DROP_STACK -> {
                // Region-thread action hook executed on the owning region tick
            }
        }
    }
}
