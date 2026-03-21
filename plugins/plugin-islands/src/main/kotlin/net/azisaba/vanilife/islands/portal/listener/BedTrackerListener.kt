package net.azisaba.vanilife.islands.portal.listener

import com.github.shynixn.mccoroutine.folia.launch
import net.azisaba.vanilife.islands.portal.LastBedStorage
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerBedLeaveEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.Plugin

internal class BedTrackerListener(
    private val plugin: Plugin,
    private val storage: LastBedStorage,
    private val resourceWorldName: String,
) : Listener {
    @EventHandler
    fun onBedLeave(event: PlayerBedLeaveEvent) {
        if (!event.shouldSetSpawnLocation()) return
        val respawnLocation = event.player.respawnLocation ?: return
        val world = respawnLocation.world ?: return
        if (world.name != resourceWorldName) return
        plugin.launch {
            storage.setLastBed(world.key.toString(), event.player.uniqueId, respawnLocation)
        }
    }

    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val respawn = event.respawnLocation
        if (respawn.world?.name != resourceWorldName) return
        plugin.launch {
            storage.setLastBed(respawn.world.key.toString(), event.player.uniqueId, respawn)
        }
    }
}
