package dev.leafcraft.goldleaf.bot;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 opt-in, region-safe synthetic player / bot SPI ({@code goldleaf-ADR-015}).
 *
 * <p>Guarantees:
 * <ul>
 *   <li><strong>Default-off fail-closed ({@code S-004}):</strong> Rejects spawn requests with
 *       {@link IllegalStateException} when {@code goldleaf.yml bots.enabled} is {@code false}.</li>
 *   <li><strong>Deterministic non-colliding UUIDs:</strong> Derived from the dedicated
 *       {@code GoldLeaf:SyntheticPlayer:} namespace.</li>
 *   <li><strong>Hard quotas:</strong> Enforces {@code bots.max-total} and
 *       {@code bots.max-per-player} atomically.</li>
 *   <li><strong>Region-owned dispatch ({@code F-001}..{@code F-003}):</strong> All spawns,
 *       teleports, actions, and despawns execute through {@link RegionDispatcher}.</li>
 *   <li><strong>Zero resource leaks ({@code S-002}):</strong> {@link #despawn(UUID)},
 *       {@link #onOwnerQuit(UUID)}, and {@link #shutdownAll()} release all active handles and
 *       chunk-loader/ticket hooks idempotently.</li>
 * </ul>
 * </p>
 */
public final class SyntheticPlayerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyntheticPlayerService.class);
    private static final String UUID_NAMESPACE_PREFIX = "GoldLeaf:SyntheticPlayer:";
    private static final SyntheticPlayerService GLOBAL = new SyntheticPlayerService();

    /**
     * Region-thread scheduling seam so spawns, teleports, and despawns execute on the target
     * chunk's owning Folia region thread ({@code F-001}..{@code F-003}, {@code S-005}).
     */
    @FunctionalInterface
    public interface RegionDispatcher {
        void executeOnRegion(String worldName, int chunkX, int chunkZ, Runnable task);
    }

    /**
     * Optional lifecycle hook for attaching/detaching NMS {@code ServerPlayer} and
     * {@code RegionizedPlayerChunkLoader} handles on the owning region thread ({@code S-002}).
     */
    public interface LifecycleBridge {
        void onAttach(SyntheticPlayerHandle handle);

        void onTeleport(SyntheticPlayerHandle handle, SyntheticPlayerHandle.PositionSnapshot target);

        void onDetach(SyntheticPlayerHandle handle);
    }

    private final ConcurrentHashMap<UUID, SyntheticPlayerHandle> activeByUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> activeNameToUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicInteger> ownerCounts = new ConcurrentHashMap<>();
    private volatile RegionDispatcher regionDispatcher = (world, chunkX, chunkZ, task) -> task.run();
    private volatile @Nullable LifecycleBridge lifecycleBridge;

    public static SyntheticPlayerService global() {
        return GLOBAL;
    }

    public static UUID deterministicUuid(final String botName) {
        Objects.requireNonNull(botName, "botName");
        final String normalized = botName.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("botName must not be blank");
        }
        return UUID.nameUUIDFromBytes((UUID_NAMESPACE_PREFIX + normalized).getBytes(StandardCharsets.UTF_8));
    }

    public void setRegionDispatcher(final RegionDispatcher dispatcher) {
        this.regionDispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public void setLifecycleBridge(final @Nullable LifecycleBridge bridge) {
        this.lifecycleBridge = bridge;
    }

    public int activeCount() {
        return this.activeByUuid.size();
    }

    public Collection<SyntheticPlayerHandle> activeHandles() {
        return Collections.unmodifiableCollection(this.activeByUuid.values());
    }

    public @Nullable SyntheticPlayerHandle get(final UUID syntheticUuid) {
        return syntheticUuid == null ? null : this.activeByUuid.get(syntheticUuid);
    }

    /**
     * Spawns a region-owned synthetic player after enforcing config opt-in and global/per-owner
     * quotas.
     */
    public CompletableFuture<SyntheticPlayerHandle> spawn(
        final String name,
        final UUID ownerUuid,
        final SyntheticPlayerHandle.PositionSnapshot spawnPosition,
        final boolean requestChunkLoading
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(spawnPosition, "spawnPosition");

        final GoldLeafConfig.Bots cfg = GoldLeafConfig.get().bots;
        if (!cfg.enabled) {
            throw new IllegalStateException(
                "GoldLeaf synthetic players are disabled (set goldleaf.yml bots.enabled: true to opt in)"
            );
        }

        final String normalizedName = name.trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 16) {
            throw new IllegalArgumentException("Synthetic player name must be 1..16 chars, got '" + name + "'");
        }

        final UUID botUuid = deterministicUuid(normalizedName);
        final String nameKey = normalizedName.toLowerCase(java.util.Locale.ROOT);
        final boolean chunkLoading = requestChunkLoading && cfg.allowChunkLoading;

        final SyntheticPlayerHandle handle = new SyntheticPlayerHandle(
            botUuid,
            normalizedName,
            ownerUuid,
            chunkLoading,
            spawnPosition
        );

        synchronized (this) {
            if (this.activeByUuid.size() >= cfg.maxTotal) {
                throw new IllegalStateException(
                    "Global synthetic player limit reached (" + cfg.maxTotal + ")"
                );
            }
            if (this.activeByUuid.containsKey(botUuid) || this.activeNameToUuid.containsKey(nameKey)) {
                throw new IllegalStateException(
                    "Synthetic player '" + normalizedName + "' is already active"
                );
            }
            final AtomicInteger ownerCounter = this.ownerCounts.computeIfAbsent(ownerUuid, ignored -> new AtomicInteger());
            if (ownerCounter.get() >= cfg.maxPerPlayer) {
                throw new IllegalStateException(
                    "Per-player synthetic player limit reached (" + cfg.maxPerPlayer + ") for owner " + ownerUuid
                );
            }
            ownerCounter.incrementAndGet();
            this.activeByUuid.put(botUuid, handle);
            this.activeNameToUuid.put(nameKey, botUuid);
        }

        final CompletableFuture<SyntheticPlayerHandle> future = new CompletableFuture<>();
        final int chunkX = (int) Math.floor(spawnPosition.x()) >> 4;
        final int chunkZ = (int) Math.floor(spawnPosition.z()) >> 4;
        try {
            this.regionDispatcher.executeOnRegion(spawnPosition.worldName(), chunkX, chunkZ, () -> {
                try {
                    final LifecycleBridge bridge = this.lifecycleBridge;
                    if (bridge != null) {
                        bridge.onAttach(handle);
                    }
                    future.complete(handle);
                } catch (final Throwable t) {
                    LOGGER.warn("Failed to attach synthetic player '{}' on owning region thread", normalizedName, t);
                    cleanupHandle(handle);
                    future.completeExceptionally(t);
                }
            });
        } catch (final Throwable t) {
            LOGGER.warn("Failed to schedule synthetic player '{}' spawn onto region thread", normalizedName, t);
            cleanupHandle(handle);
            future.completeExceptionally(t);
        }
        return future;
    }

    /**
     * Enqueues a composable action primitive onto the synthetic player's owning region thread.
     */
    public CompletableFuture<Integer> dispatchAction(
        final UUID syntheticUuid,
        final SyntheticPlayerAction action
    ) {
        Objects.requireNonNull(syntheticUuid, "syntheticUuid");
        Objects.requireNonNull(action, "action");
        final SyntheticPlayerHandle handle = this.activeByUuid.get(syntheticUuid);
        if (handle == null || !handle.isActive()) {
            throw new IllegalStateException("Synthetic player not active: " + syntheticUuid);
        }
        handle.enqueueAction(action);
        final SyntheticPlayerHandle.PositionSnapshot pos = handle.positionSnapshot();
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        this.regionDispatcher.executeOnRegion(
            pos.worldName(),
            (int) Math.floor(pos.x()) >> 4,
            (int) Math.floor(pos.z()) >> 4,
            () -> {
                try {
                    future.complete(handle.drainActionsOnRegionThread());
                } catch (final Throwable t) {
                    LOGGER.warn("Error executing action for synthetic player '{}'", handle.name(), t);
                    future.completeExceptionally(t);
                }
            }
        );
        return future;
    }

    /**
     * Teleports a synthetic player across region or dimension boundaries via the target region's
     * dispatcher ({@code F-001}..{@code F-003}).
     */
    public CompletableFuture<SyntheticPlayerHandle.PositionSnapshot> teleport(
        final UUID syntheticUuid,
        final SyntheticPlayerHandle.PositionSnapshot target
    ) {
        Objects.requireNonNull(syntheticUuid, "syntheticUuid");
        Objects.requireNonNull(target, "target");
        final SyntheticPlayerHandle handle = this.activeByUuid.get(syntheticUuid);
        if (handle == null || !handle.isActive()) {
            throw new IllegalStateException("Synthetic player not active: " + syntheticUuid);
        }
        final CompletableFuture<SyntheticPlayerHandle.PositionSnapshot> future = new CompletableFuture<>();
        this.regionDispatcher.executeOnRegion(
            target.worldName(),
            (int) Math.floor(target.x()) >> 4,
            (int) Math.floor(target.z()) >> 4,
            () -> {
                try {
                    if (!handle.isActive()) {
                        future.completeExceptionally(
                            new IllegalStateException("Synthetic player despawned before teleport completed")
                        );
                        return;
                    }
                    final LifecycleBridge bridge = this.lifecycleBridge;
                    if (bridge != null) {
                        bridge.onTeleport(handle, target);
                    }
                    handle.updatePosition(target);
                    future.complete(target);
                } catch (final Throwable t) {
                    LOGGER.warn("Failed to teleport synthetic player '{}'", handle.name(), t);
                    future.completeExceptionally(t);
                }
            }
        );
        return future;
    }

    /**
     * Idempotently despawns a synthetic player and releases all chunk-loader / ticket hooks
     * ({@code S-002}).
     */
    public boolean despawn(final UUID syntheticUuid) {
        if (syntheticUuid == null) {
            return false;
        }
        final SyntheticPlayerHandle handle = this.activeByUuid.get(syntheticUuid);
        if (handle == null) {
            return false;
        }
        return cleanupHandle(handle);
    }

    /**
     * Despawns all synthetic players owned by a disconnecting player ({@code S-002}).
     */
    public int onOwnerQuit(final UUID ownerUuid) {
        if (ownerUuid == null) {
            return 0;
        }
        final List<SyntheticPlayerHandle> owned = new ArrayList<>();
        for (final SyntheticPlayerHandle handle : this.activeByUuid.values()) {
            if (ownerUuid.equals(handle.ownerUuid())) {
                owned.add(handle);
            }
        }
        int removed = 0;
        for (final SyntheticPlayerHandle handle : owned) {
            if (cleanupHandle(handle)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Despawns all active synthetic players during server shutdown ({@code S-002}).
     */
    public int shutdownAll() {
        final List<SyntheticPlayerHandle> snapshot = new ArrayList<>(this.activeByUuid.values());
        int removed = 0;
        for (final SyntheticPlayerHandle handle : snapshot) {
            if (cleanupHandle(handle)) {
                removed++;
            }
        }
        this.ownerCounts.clear();
        return removed;
    }

    private boolean cleanupHandle(final SyntheticPlayerHandle handle) {
        if (!handle.markDespawned()) {
            return false;
        }
        synchronized (this) {
            this.activeByUuid.remove(handle.uuid(), handle);
            this.activeNameToUuid.remove(handle.name().toLowerCase(java.util.Locale.ROOT), handle.uuid());
            final AtomicInteger counter = this.ownerCounts.get(handle.ownerUuid());
            if (counter != null && counter.decrementAndGet() <= 0) {
                this.ownerCounts.remove(handle.ownerUuid(), counter);
            }
        }
        final LifecycleBridge bridge = this.lifecycleBridge;
        if (bridge != null) {
            final SyntheticPlayerHandle.PositionSnapshot pos = handle.positionSnapshot();
            try {
                this.regionDispatcher.executeOnRegion(
                    pos.worldName(),
                    (int) Math.floor(pos.x()) >> 4,
                    (int) Math.floor(pos.z()) >> 4,
                    () -> {
                        try {
                            bridge.onDetach(handle);
                        } catch (final Throwable t) {
                            LOGGER.warn("Error detaching synthetic player '{}' on region thread", handle.name(), t);
                        }
                    }
                );
            } catch (final Throwable t) {
                LOGGER.warn("Error scheduling detach for synthetic player '{}'", handle.name(), t);
            }
        }
        return true;
    }
}
