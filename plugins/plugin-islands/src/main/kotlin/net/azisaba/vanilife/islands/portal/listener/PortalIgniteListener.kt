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

            if (world.name == resourceWorldName) {
                val success = teleporter.teleportResourceToIsland(player)
                if (!success) {
                    player.sendMessage(Component.text("Could not return to your island."))
                }
                return@launch
            }

            val islandPos = IslandPos.fromBlockPos(player.location.blockX, player.location.blockZ)
            val success = teleporter.teleportIslandToResource(player, islandPos)
            if (success) {
                if (world.getBlockAt(event.block.location).type == Material.FIRE) {
                    world.getBlockAt(event.block.location).type = Material.AIR
                }
            } else {
                player.sendMessage(Component.text("Could not connect to resource world."))
            }
        }
    }
}
