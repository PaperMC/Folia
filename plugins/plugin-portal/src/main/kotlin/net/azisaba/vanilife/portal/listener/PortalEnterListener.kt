package net.azisaba.vanilife.portal.listener

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.world.IslandPos
import net.azisaba.vanilife.portal.exits.ExitForcer
import net.azisaba.vanilife.portal.exits.getExitAnchor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPortalEnterEvent
import org.bukkit.plugin.Plugin

internal class PortalEnterListener(private val forcer: ExitForcer, private val plugin: Plugin) : Listener {
    @EventHandler
    fun onEntityPortalEnter(event: EntityPortalEnterEvent) {
        event.isCancelled = true

        val player = event.entity as? Player
        if (player == null || event.location.world != Vanilife.getIslandsWorld()) {
            return
        }

        plugin.launch(plugin.regionDispatcher(forcer.world, 0, 0)) {
            val exitAnchor = forcer.world.getExitAnchor(player)
            if (exitAnchor?.teleportOrClear(forcer.world, player) != true) {
                val safeLocation = forcer.findSafeLocation(IslandPos(0, 0), plugin)
                player.teleportAsync(safeLocation)
            }
        }
    }
}
