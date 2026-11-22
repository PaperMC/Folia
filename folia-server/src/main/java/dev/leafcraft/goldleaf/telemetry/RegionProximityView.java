package dev.leafcraft.goldleaf.telemetry;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * Region-local read-only proximity and entity-count view ({@code goldleaf-ADR-006} §1.4).
 *
 * <p>Reads exclusively from the owning region's {@link RegionizedWorldData} local player and entity
 * lists: zero synchronous chunk loads, zero cross-region world locks, and zero global sweeps
 * ({@code S-005}, {@code F-001}..{@code F-003}).</p>
 */
public final class RegionProximityView {

    public record ChunkEntityCounts(int itemCount, int livingCount, int projectileCount, int totalCount) {
        public static final ChunkEntityCounts EMPTY = new ChunkEntityCounts(0, 0, 0, 0);
    }

    private RegionProximityView() {
    }

    /**
     * Computes the squared horizontal distance from the nearest player in {@code regionData} to the
     * center of chunk {@code (chunkX, chunkZ)}, or {@link Double#POSITIVE_INFINITY} if no local
     * player is present in the region.
     */
    public static double nearestPlayerDistanceSquared(
        final RegionizedWorldData regionData,
        final int chunkX,
        final int chunkZ
    ) {
        Objects.requireNonNull(regionData, "regionData");
        final double centerX = (chunkX << 4) + 8.0;
        final double centerZ = (chunkZ << 4) + 8.0;
        double minDistSq = Double.POSITIVE_INFINITY;
        for (final ServerPlayer player : regionData.getLocalPlayers()) {
            final double dx = player.getX() - centerX;
            final double dz = player.getZ() - centerZ;
            final double distSq = dx * dx + dz * dz;
            if (distSq < minDistSq) {
                minDistSq = distSq;
            }
        }
        return minDistSq;
    }

    /**
     * Pure helper for computing squared horizontal distance from a list of {@code [x, z]} positions
     * to chunk center {@code (chunkX, chunkZ)} (used by unit tests without booting NMS).
     */
    public static double nearestPlayerDistanceSquared(
        final double[] playerXs,
        final double[] playerZs,
        final int chunkX,
        final int chunkZ
    ) {
        final int len = Math.min(playerXs.length, playerZs.length);
        final double centerX = (chunkX << 4) + 8.0;
        final double centerZ = (chunkZ << 4) + 8.0;
        double minDistSq = Double.POSITIVE_INFINITY;
        for (int i = 0; i < len; i++) {
            final double dx = playerXs[i] - centerX;
            final double dz = playerZs[i] - centerZ;
            final double distSq = dx * dx + dz * dz;
            if (distSq < minDistSq) {
                minDistSq = distSq;
            }
        }
        return minDistSq;
    }

    /**
     * Counts entities currently tracked in {@code regionData} that reside in chunk
     * {@code (chunkX, chunkZ)}, categorized into {@code ITEM}, {@code LIVING}, and
     * {@code PROJECTILE}.
     */
    public static ChunkEntityCounts countChunkEntities(
        final RegionizedWorldData regionData,
        final int chunkX,
        final int chunkZ
    ) {
        Objects.requireNonNull(regionData, "regionData");
        int items = 0;
        int living = 0;
        int projectiles = 0;
        int total = 0;
        for (final Entity entity : regionData.getLoadedEntities()) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            if ((entity.getBlockX() >> 4) != chunkX || (entity.getBlockZ() >> 4) != chunkZ) {
                continue;
            }
            total++;
            if (entity instanceof ItemEntity) {
                items++;
            } else if (entity instanceof LivingEntity) {
                living++;
            } else if (entity instanceof Projectile) {
                projectiles++;
            }
        }
        return total == 0 ? ChunkEntityCounts.EMPTY : new ChunkEntityCounts(items, living, projectiles, total);
    }
}
