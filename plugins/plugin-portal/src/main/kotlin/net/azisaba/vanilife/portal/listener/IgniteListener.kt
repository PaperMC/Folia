package net.azisaba.vanilife.portal.listener

import com.github.shynixn.mccoroutine.folia.launch
import net.azisaba.vanilife.portal.ResourcePortals
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.plugin.Plugin

class IgniteListener(private val plugin: Plugin) : Listener {
    @EventHandler
    fun onBlockIgnite(event: BlockIgniteEvent) {
        val block = event.block.getRelative(BlockFace.DOWN)
        plugin.launch {
            val detected = ResourcePortals.finder.findPortal(plugin, block.location) ?: return@launch
            ResourcePortals.createWithAnimation(plugin, detected)
        }
    }
}
