package net.azisaba.vanilife.portal.listener

import com.github.shynixn.mccoroutine.folia.launch
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.islands.IslandPos
import net.azisaba.vanilife.portal.PortalForcer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPortalEnterEvent
import org.bukkit.plugin.Plugin

internal class PortalEnterListener(private val forcer: PortalForcer, private val plugin: Plugin) : Listener {
    @EventHandler
    fun onEntityPortalEnter(event: EntityPortalEnterEvent) {
        event.isCancelled = true

        val player = event.entity as? Player
        if (player == null || event.location.world != Vanilife.getIslandsWorld()) {
            return
        }

        plugin.launch {
            val safeLocation = forcer.findSafeLocation(IslandPos(0, 0), plugin)
            player.teleportAsync(safeLocation)
        }
    }
}
