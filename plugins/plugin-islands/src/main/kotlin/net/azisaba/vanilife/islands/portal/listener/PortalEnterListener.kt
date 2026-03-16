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
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

internal class PortalEnterListener(
    private val plugin: Plugin,
    private val resourceWorldName: String,
    private val teleporter: ResourceTeleporter,
) : Listener {
    private val cooldowns: ConcurrentHashMap<UUID, Long> = ConcurrentHashMap()

    @EventHandler(ignoreCancelled = true)
    fun onPlayerPortal(event: PlayerPortalEvent) {
        val player = event.player

        // Quick synchronous check: if nearby blocks are plugin-created portal blocks
        val base = event.from.block
        val candidates = listOf(base, base.getRelative(BlockFace.DOWN), base.getRelative(BlockFace.UP))
        if (!candidates.any { it.type == Material.NETHER_PORTAL && it.hasMetadata("vanilife_portal") }) {
            return // not our portal -> allow vanilla handling
        }

        // cancel vanilla portal handling; we'll teleport manually
        event.isCancelled = true

        val now = System.currentTimeMillis()
        val until = cooldowns[player.uniqueId] ?: 0L
        if (now < until) return
        cooldowns[player.uniqueId] = now + 3000L // 3s cooldown

        plugin.launch {
            try {
                val detected = ResourcePortals.finder.findPortal(plugin, base.location) ?: run {
                    player.sendMessage(Component.text("Portal not recognized."))
                    return@launch
                }
                val world = detected.world
                val success = if (world.name == resourceWorldName) {
                    teleporter.teleportResourceToIsland(player)
                } else {
                    val islandPos = IslandPos.fromBlockPos(player.location.blockX, player.location.blockZ)
                    teleporter.teleportIslandToResource(player, islandPos)
                }
                if (!success) {
                    plugin.logger.log(Level.WARNING, "Portal teleport failed for player=${player.name} to world=${world.name}")
                    player.sendMessage(Component.text("Could not connect to resource world."))
                }
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Portal enter handling failed", e)
                player.sendMessage(Component.text("Teleport failed (see server logs)."))
            }
        }
    }
}
