package net.azisaba.vanilife.islands.portal.listener

import com.github.shynixn.mccoroutine.folia.launch
import net.azisaba.vanilife.islands.portal.LastBedStorage
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.Plugin

internal class BedTrackerListener(
    private val plugin: Plugin,
    private val storage: LastBedStorage,
    private val resourceWorldName: String,
) : Listener {
    @EventHandler
    fun onBedEnter(event: PlayerBedEnterEvent) {
        val world = event.player.world
        if (world.name != resourceWorldName) return
        val bedLocation = event.bed.location
        plugin.launch {
            storage.setLastBed(world.key.toString(), event.player.uniqueId, bedLocation)
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
