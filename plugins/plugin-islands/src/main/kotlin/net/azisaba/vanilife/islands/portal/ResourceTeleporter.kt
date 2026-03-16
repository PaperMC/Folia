package net.azisaba.vanilife.islands.portal

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import net.azisaba.vanilife.islands.IslandManager
import net.azisaba.vanilife.islands.IslandPos
import net.azisaba.vanilife.islands.storage.resolveSpawnPoint
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

internal class ResourceTeleporter(
    private val plugin: Plugin,
    private val cache: ResourceSpawnCache,
    private val lastBedStorage: LastBedStorage,
    private val islandManager: IslandManager,
    private val resourceWorldName: String,
) {
    suspend fun teleportIslandToResource(player: Player, islandPos: IslandPos): Boolean {
        val world = Bukkit.getWorld(resourceWorldName)
            ?: run {
                plugin.logger.warning("Resource teleport failed: resource world '$resourceWorldName' not found for player=${player.name}")
                return false
            }
        val cached = cache.getOrCompute(islandPos, world)
        val safe = cache.findSafeLocation(world, cached.spawnX, cached.spawnY, cached.spawnZ, 8)
            ?: Location(world, cached.spawnX + 0.5, cached.spawnY.toDouble(), cached.spawnZ + 0.5)

        if (safe.blockX != cached.spawnX || safe.blockY != cached.spawnY || safe.blockZ != cached.spawnZ) {
            cache.overwrite(islandPos, world, safe.blockX, safe.blockY, safe.blockZ)
        }

        return try {
            withContext(plugin.regionDispatcher(safe)) {
                world.getChunkAtAsync(safe.blockX shr 4, safe.blockZ shr 4, true).await()
                val ok = player.teleportAsync(safe).await()
                plugin.logger.info("teleportIslandToResource: player=${player.name}, ok=$ok, target=$safe")
                ok
            }
        } catch (e: Exception) {
            plugin.logger.log(java.util.logging.Level.SEVERE, "teleportIslandToResource failed for player=${player.name} to=$safe", e)
            false
        }
    }

    suspend fun teleportResourceToIsland(player: Player): Boolean {
        val resourceWorld = Bukkit.getWorld(resourceWorldName)
            ?: run {
                plugin.logger.warning("Resource teleport failed: resource world '$resourceWorldName' not found for player=${player.name}")
                return false
            }
        val bed = lastBedStorage.getLastBed(resourceWorld.key.toString(), player.uniqueId)
        if (bed != null) {
            val bedLocation = Location(resourceWorld, bed.x + 0.5, bed.y.toDouble(), bed.z + 0.5, bed.yaw, bed.pitch)
            if (cache.findSafeLocation(resourceWorld, bed.x, bed.y, bed.z, 2) != null) {
                return try {
                    withContext(plugin.regionDispatcher(bedLocation)) {
                        resourceWorld.getChunkAtAsync(bedLocation.blockX shr 4, bedLocation.blockZ shr 4, true).await()
                        val ok = player.teleportAsync(bedLocation).await()
                        plugin.logger.info("teleportResourceToIsland(bed): player=${player.name}, ok=$ok, target=$bedLocation")
                        ok
                    }
                } catch (e: Exception) {
                    plugin.logger.log(java.util.logging.Level.SEVERE, "teleportResourceToIsland to bed failed for player=${player.name} to=$bedLocation", e)
                    false
                }
            }
            lastBedStorage.clear(resourceWorld.key.toString(), player.uniqueId)
        }

        val island = islandManager.lookupByOwner(player.uniqueId) ?: return false
        val islandSpawn = island.primaryData.resolveSpawnPoint(island.pos)
        return try {
            val ok = player.teleportAsync(islandSpawn).await()
            plugin.logger.info("teleportResourceToIsland(fallback island): player=${player.name}, ok=$ok, target=$islandSpawn")
            ok
        } catch (e: Exception) {
            plugin.logger.log(java.util.logging.Level.SEVERE, "teleportResourceToIsland to island spawn failed for player=${player.name} to=$islandSpawn", e)
            false
        }
    }
}
