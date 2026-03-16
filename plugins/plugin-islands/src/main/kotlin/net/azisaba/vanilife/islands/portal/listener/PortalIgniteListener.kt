package net.azisaba.vanilife.islands.portal.listener

import com.github.shynixn.mccoroutine.folia.launch
import net.azisaba.vanilife.islands.IslandPos
import net.azisaba.vanilife.islands.portal.ResourcePortals
import net.azisaba.vanilife.islands.portal.ResourceTeleporter
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.plugin.Plugin

internal class PortalIgniteListener(
    private val plugin: Plugin,
    private val resourceWorldName: String,
    private val teleporter: ResourceTeleporter,
) : Listener {
    @EventHandler
    fun onBlockIgnite(event: BlockIgniteEvent) {
        val baseBlock = event.block.getRelative(BlockFace.DOWN)
        plugin.launch {
            val detected = ResourcePortals.finder.findPortal(plugin, baseBlock.location) ?: return@launch
            val player = event.player ?: return@launch
            val world = detected.world

            // On ignite: only create the portal visuals/blocks. Actual teleport occurs when
            // a player enters the portal (PlayerPortalEvent). This prevents surprise immediate teleports.
            ResourcePortals.createWithAnimation(plugin, detected)

            // Extinguish the ignited fire on the region dispatcher so we modify world state safely
            val fireLocation = event.block.location
            plugin.launch(plugin.regionDispatcher(fireLocation)) {
                if (detected.world.getBlockAt(fireLocation).type == Material.FIRE) {
                    detected.world.getBlockAt(fireLocation).type = Material.AIR
                }
            }
        }
    }
}
