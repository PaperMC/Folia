package dev.leafcraft.goldleaf.bot;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 Folia region-scheduler dispatcher and lifecycle bridge for
 * {@link SyntheticPlayerService} ({@code goldleaf-ADR-015}).
 *
 * <p>Routes synthetic player spawns, teleports, actions, and despawns onto the target chunk's
 * owning Folia region thread when a live server is active ({@code F-001}..{@code F-003},
 * {@code S-005}), while tracking attached chunk tickets/state for clean {@code S-002} teardown.</p>
 */
public final class FoliaSyntheticPlayerBridge implements SyntheticPlayerService.RegionDispatcher,
    SyntheticPlayerService.LifecycleBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(FoliaSyntheticPlayerBridge.class);

    private final ConcurrentHashMap<UUID, SyntheticPlayerHandle.PositionSnapshot> attachedPositions =
        new ConcurrentHashMap<>();

    public static void install(final SyntheticPlayerService service) {
        Objects.requireNonNull(service, "service");
        final FoliaSyntheticPlayerBridge bridge = new FoliaSyntheticPlayerBridge();
        service.setRegionDispatcher(bridge);
        service.setLifecycleBridge(bridge);
    }

    @Override
    public void executeOnRegion(
        final String worldName,
        final int chunkX,
        final int chunkZ,
        final Runnable task
    ) {
        Objects.requireNonNull(task, "task");
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null || Bukkit.getServer() == null) {
            // Headless unit-test mode (no live Folia server booted).
            task.run();
            return;
        }
        final World world = Bukkit.getWorld(worldName);
        if (world == null) {
            LOGGER.warn("Rejecting synthetic player task for unknown world {} ({}, {}) (fail-closed, ADR-015)",
                worldName, chunkX, chunkZ);
            throw new IllegalStateException("Unknown world for synthetic player task: " + worldName);
        }
        final ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, chunkX, chunkZ)) {
            task.run();
            return;
        }
        try {
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueChunkTask(
                level,
                chunkX,
                chunkZ,
                task
            );
        } catch (final Throwable t) {
            LOGGER.warn("Failed scheduling synthetic player task onto owning region for {} ({}, {}); failing closed",
                worldName, chunkX, chunkZ, t);
            throw new IllegalStateException(
                "Failed scheduling synthetic player task for " + worldName + " (" + chunkX + ", " + chunkZ + ")",
                t
            );
        }
    }

    @Override
    public void onAttach(final SyntheticPlayerHandle handle) {
        Objects.requireNonNull(handle, "handle");
        final SyntheticPlayerHandle.PositionSnapshot pos = handle.positionSnapshot();
        this.attachedPositions.put(handle.uuid(), pos);
        if (handle.isChunkLoadingAllowed()) {
            updateChunkForceLoad(pos, true);
        }
    }

    @Override
    public void onTeleport(
        final SyntheticPlayerHandle handle,
        final SyntheticPlayerHandle.PositionSnapshot target
    ) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(target, "target");
        final SyntheticPlayerHandle.PositionSnapshot prev = this.attachedPositions.put(handle.uuid(), target);
        if (handle.isChunkLoadingAllowed()) {
            if (prev != null && (chunkCoord(prev.x()) != chunkCoord(target.x())
                || chunkCoord(prev.z()) != chunkCoord(target.z())
                || !prev.worldName().equals(target.worldName()))) {
                updateChunkForceLoad(prev, false);
            }
            updateChunkForceLoad(target, true);
        }
    }

    @Override
    public void onDetach(final SyntheticPlayerHandle handle) {
        Objects.requireNonNull(handle, "handle");
        final SyntheticPlayerHandle.PositionSnapshot prev = this.attachedPositions.remove(handle.uuid());
        if (handle.isChunkLoadingAllowed() && prev != null) {
            updateChunkForceLoad(prev, false);
        }
    }

    public int attachedCount() {
        return this.attachedPositions.size();
    }

    private static int chunkCoord(final double coord) {
        return ((int) Math.floor(coord)) >> 4;
    }

    private static void updateChunkForceLoad(
        final SyntheticPlayerHandle.PositionSnapshot pos,
        final boolean forceLoaded
    ) {
        try {
            final MinecraftServer server = MinecraftServer.getServer();
            if (server == null) {
                return;
            }
            final int cx = chunkCoord(pos.x());
            final int cz = chunkCoord(pos.z());
            for (final ServerLevel level : server.getAllLevels()) {
                if (level.getWorld() != null && pos.worldName().equals(level.getWorld().getName())) {
                    if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, cx, cz)) {
                        level.setChunkForced(cx, cz, forceLoaded);
                    }
                    return;
                }
            }
        } catch (final Throwable t) {
            LOGGER.warn("Failed updating synthetic player chunk force-load at {} ({}, {})",
                pos.worldName(), chunkCoord(pos.x()), chunkCoord(pos.z()), t);
        }
    }
}
