package net.azisaba.vanilife.islands.portal.listener

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.azisaba.vanilife.islands.IslandManager
import net.azisaba.vanilife.islands.portal.ResourceSpawnCache
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin

internal class PlayerJoinPrecomputeListener(
    private val plugin: Plugin,
    private val cache: ResourceSpawnCache,
    private val islandManager: IslandManager,
    private val resourceWorldName: String,
) : Listener {
    private val scope = CoroutineScope(Dispatchers.Default)

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        scope.launch {
            val island = islandManager.lookupByOwner(player.uniqueId)
            if (island != null) {
                val islandPos = island.pos
                val resourceWorld = plugin.server.getWorld(resourceWorldName) ?: return@launch
                cache.ensureComputedAsync(islandPos, resourceWorld)
            }
        }
    }
}
