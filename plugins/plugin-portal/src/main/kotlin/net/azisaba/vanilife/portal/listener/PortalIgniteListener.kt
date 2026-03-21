package net.azisaba.vanilife.portal.listener

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.portal.finder.PortalFinder
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.plugin.Plugin

internal class PortalIgniteListener(private val finder: PortalFinder, private val plugin: Plugin) : Listener {
    @EventHandler
    fun onBlockIgnite(event: BlockIgniteEvent) {
        if (event.block.world != Vanilife.getIslandsWorld()) return

        val baseBlock = event.block.getRelative(BlockFace.DOWN).takeIf {
            it.type == finder.frame
        } ?: return

        plugin.launch(plugin.regionDispatcher(baseBlock.location)) {
            val detected = finder.findPortal(baseBlock.location, plugin) ?: return@launch
            detected.fillPortalWithAnimation(plugin)
        }
    }
}
