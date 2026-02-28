package net.azisaba.vanilife.islands.listener

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import kotlinx.coroutines.runBlocking
import net.azisaba.vanilife.islands.island.IslandManager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import kotlin.uuid.toKotlinUuid

class AsyncPlayerSpawnLocationListener(private val access: IslandManager) : Listener {
    @EventHandler
    fun onAsyncPlayerSpawnLocation(event: AsyncPlayerSpawnLocationEvent) {
        val playerUuid = event.connection.profile.id?.toKotlinUuid() ?: error("Unable to resolve player UUID")
        runBlocking {
            val island = access.lookupOrCreateByOwner(playerUuid)
            event.spawnLocation = island.spawnPoint
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        runBlocking {
            val island = access.lookupOrCreateByOwner(event.player.uniqueId.toKotlinUuid())
            island.addPlayer(event.player)
        }
    }
}
