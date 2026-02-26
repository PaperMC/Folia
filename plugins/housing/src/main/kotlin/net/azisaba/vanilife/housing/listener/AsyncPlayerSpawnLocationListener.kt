package net.azisaba.vanilife.housing.listener

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import kotlinx.coroutines.runBlocking
import net.azisaba.vanilife.housing.islands.IslandAccess
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import kotlin.uuid.toKotlinUuid

class AsyncPlayerSpawnLocationListener(private val access: IslandAccess) : Listener {
    @EventHandler
    fun onAsyncPlayerSpawnLocation(event: AsyncPlayerSpawnLocationEvent) {
        val playerUuid = event.connection.profile.id?.toKotlinUuid() ?: error("Unable to resolve player UUID")
        runBlocking {
            val island = access.lookupOrCreateByOwner(playerUuid)
            event.spawnLocation = island.spawnPoint
        }
    }
}
