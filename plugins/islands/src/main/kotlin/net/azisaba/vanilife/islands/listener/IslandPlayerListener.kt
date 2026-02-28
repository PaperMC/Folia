package net.azisaba.vanilife.islands.listener

import com.github.shynixn.mccoroutine.folia.launch
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import kotlinx.coroutines.runBlocking
import net.azisaba.vanilife.islands.IslandManager
import net.azisaba.vanilife.islands.addPlayer
import net.azisaba.vanilife.islands.removePlayer
import net.azisaba.vanilife.islands.storage.resolveSpawnPoint
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin

internal class IslandPlayerListener(private val plugin: Plugin, private val service: IslandManager) : Listener {
    @EventHandler
    fun onAsyncPlayerSpawnLocation(event: AsyncPlayerSpawnLocationEvent) {
        val playerUuid = event.connection.profile.id ?: return
        runBlocking {
            val island = service.lookupOrCreateByOwner(playerUuid)
            event.spawnLocation = island.primaryData.resolveSpawnPoint(island.pos)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        plugin.launch {
            val island = service.lookupByOwner(player.uniqueId)
            island?.addPlayer(player)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        plugin.launch {
            val island = service.lookupByOwner(player.uniqueId)
            island?.removePlayer(player)
        }
    }
}
