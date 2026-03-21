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
    suspend fun teleportIslandToResource(
        player: Player,
        islandPos: IslandPos,
    ): Boolean {
        val world =
            Bukkit.getWorld(resourceWorldName)
                ?: run {
                    plugin.slF4JLogger.warn(
                        "Resource teleport failed: resource world '$resourceWorldName' not found for player=${player.name}",
                    )
                    return false
                }
        val lastBed = lastBedStorage.getLastBed(world.key.toString(), player.uniqueId)
        val safe =
            if (lastBed != null) {
                cache.findSafeLocation(world, lastBed.x, lastBed.y, lastBed.z, 2)
                    ?: Location(world, lastBed.x + 0.5, lastBed.y.toDouble(), lastBed.z + 0.5, lastBed.yaw, lastBed.pitch)
            } else {
                val deferred = cache.ensureComputedAsync(islandPos, world)
                if (!deferred.isCompleted) {
                    player.sendMessage(org.bukkit.ChatColor.YELLOW.toString() + "Computing resource spawn, please wait...")
                }
                val cached = deferred.await()
                val resolved =
                    cache.findSafeLocation(world, cached.spawnX, cached.spawnY, cached.spawnZ, 8)
                        ?: Location(world, cached.spawnX + 0.5, cached.spawnY.toDouble(), cached.spawnZ + 0.5)

                if (resolved.blockX != cached.spawnX || resolved.blockY != cached.spawnY || resolved.blockZ != cached.spawnZ) {
                    cache.overwrite(islandPos, world, resolved.blockX, resolved.blockY, resolved.blockZ)
                }
                resolved
            }

        if (lastBed != null && !cache.findSafeLocation(world, lastBed.x, lastBed.y, lastBed.z, 2).let { it != null }) {
            plugin.slF4JLogger.debug("Last bed location was not safe for player={}, falling back to direct bed location", player.name)
        }

        return try {
            withContext(plugin.regionDispatcher(safe)) {
                world.getChunkAtAsync(safe.blockX shr 4, safe.blockZ shr 4, true).await()
                val ok = player.teleportAsync(safe).await()
                plugin.slF4JLogger.debug("teleportIslandToResource: player={}, ok={}, target={}", player.name, ok, safe)
                ok
            }
        } catch (e: Exception) {
            plugin.slF4JLogger.error("teleportIslandToResource failed for player=${player.name} to=$safe", e)
            false
        }
    }

    suspend fun teleportResourceToIsland(player: Player): Boolean {
        val resourceWorld =
            Bukkit.getWorld(resourceWorldName)
                ?: run {
                    plugin.slF4JLogger.warn(
                        "Resource teleport failed: resource world '$resourceWorldName' not found for player=${player.name}",
                    )
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
                        plugin.slF4JLogger.debug(
                            "teleportResourceToIsland(bed): player={}, ok={}, target={}",
                            player.name,
                            ok,
                            bedLocation,
                        )
                        ok
                    }
                } catch (e: Exception) {
                    plugin.slF4JLogger.error("teleportResourceToIsland to bed failed for player=${player.name} to=$bedLocation", e)
                    false
                }
            }
            lastBedStorage.clear(resourceWorld.key.toString(), player.uniqueId)
        }

        val island = islandManager.lookupByOwner(player.uniqueId) ?: return false
        val islandSpawn = island.primaryData.resolveSpawnPoint(island.pos)
        return try {
            withContext(plugin.regionDispatcher(islandSpawn)) {
                islandSpawn.world.getChunkAtAsync(islandSpawn.blockX shr 4, islandSpawn.blockZ shr 4, true).await()
                val ok = player.teleportAsync(islandSpawn).await()
                plugin.slF4JLogger.debug(
                    "teleportResourceToIsland(fallback island): player={}, ok={}, target={}",
                    player.name,
                    ok,
                    islandSpawn,
                )
                ok
            }
        } catch (e: Exception) {
            plugin.slF4JLogger.error("teleportResourceToIsland to island spawn failed for player=${player.name} to=$islandSpawn", e)
            false
        }
    }
}
