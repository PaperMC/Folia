package net.azisaba.vanilife.islands.portal.listener

import com.github.shynixn.mccoroutine.folia.launch
import net.azisaba.vanilife.islands.IslandPos
import net.azisaba.vanilife.islands.portal.ResourcePortals
import net.azisaba.vanilife.islands.portal.ResourceTeleporter
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPortalEnterEvent
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

    // Helper to format basic player info for logs
    private fun playerInfoString(
        playerName: String,
        playerId: UUID,
        world: String?,
        x: Int,
        y: Int,
        z: Int,
    ): String = "player=$playerName uuid=$playerId loc=${world ?: "null"}:$x,$y,$z"

    @EventHandler(ignoreCancelled = true)
    fun onPlayerPortal(event: EntityPortalEnterEvent) {
        val player = event.entity as? Player ?: return
        // Prepare compact player info and log entry
        val pInfo =
            playerInfoString(
                player.name,
                player.uniqueId,
                player.world.name,
                player.location.blockX,
                player.location.blockY,
                player.location.blockZ,
            )

        // Quick synchronous check: if nearby blocks are plugin-created portal blocks
        // or if the portal frame (prismarine) exists nearby. This avoids a race where
        // the animation coroutine hasn't yet placed portal blocks / metadata and
        // vanilla portal handling (Nether) runs instead.
        val base = event.location.block
        val candidates = listOf(base, base.getRelative(BlockFace.DOWN), base.getRelative(BlockFace.UP))

        // Log candidate block types and positions
        val candidateInfo = candidates.joinToString(separator = "; ") { b -> "${b.type}@${b.x},${b.y},${b.z}" }
        plugin.logger.log(Level.INFO, "PortalEnterListener: candidate blocks: $candidateInfo")

        val hasPortalMeta = candidates.any { it.type == Material.NETHER_PORTAL && it.hasMetadata("vanilife_portal") }

        val hasFrameNearby =
            run {
                // scan a small neighborhood for the configured frame block to avoid false positives
                // (prismarine is the configured frame block in ResourcePortals.FRAME_BLOCK)
                for (dx in -2..2) {
                    for (dy in -1..1) {
                        for (dz in -2..2) {
                            if (base.getRelative(dx, dy, dz).type == ResourcePortals.FRAME_BLOCK) return@run true
                        }
                    }
                }
                false
            }

        val portalFrameInfo = "frame=${ResourcePortals.FRAME_BLOCK}"
        plugin.logger.log(Level.INFO, "PortalEnterListener: hasPortalMeta=$hasPortalMeta hasFrameNearby=$hasFrameNearby $portalFrameInfo")

        if (!hasPortalMeta && !hasFrameNearby) {
            plugin.logger.log(Level.INFO, "PortalEnterListener: not our portal; allowing vanilla handling for $pInfo")
            return // not our portal -> allow vanilla handling
        }

        // cancel vanilla portal handling; we'll teleport manually
        event.isCancelled = true
        plugin.logger.log(Level.INFO, "PortalEnterListener: cancelled vanilla handling for $pInfo")

        val now = System.currentTimeMillis()
        val until = cooldowns[player.uniqueId] ?: 0L
        if (now < until) {
            val remaining = until - now
            plugin.logger.log(Level.INFO, "PortalEnterListener: on cooldown for $pInfo remaining=${remaining}ms")
            return
        }
        cooldowns[player.uniqueId] = now + 3000L // 3s cooldown
        plugin.logger.log(Level.INFO, "PortalEnterListener: set cooldown until=${cooldowns[player.uniqueId]} for $pInfo")

        plugin.launch {
            try {
                plugin.logger.log(Level.INFO, "PortalEnterListener: coroutine started for $pInfo")

                // Log detected portal details (best-effort)
//                plugin.logger.log(Level.INFO, "PortalEnterListener: portal detected=$detected world=${detected.world.name} for $pInfo")

                val world = player.world
                val success =
                    if (world.name == resourceWorldName) {
                        val msgRes = "PortalEnterListener: resource world=${world.name} -> teleport resource->island for $pInfo"
                        plugin.logger.log(Level.INFO, msgRes)
                        teleporter.teleportResourceToIsland(player)
                    } else {
                        val islandPos = IslandPos.fromBlockPos(player.location.blockX, player.location.blockZ)
                        val msgNon = "PortalEnterListener: non-resource=${world.name} -> teleport island->resource for $pInfo"
                        plugin.logger.log(Level.INFO, "$msgNon islandPos=$islandPos")
                        teleporter.teleportIslandToResource(player, islandPos)
                    }
                if (!success) {
                    plugin.logger.log(Level.WARNING, "PortalEnterListener: portal teleport failed to ${world.name} for $pInfo")
                    player.sendMessage(Component.text("Could not connect to resource world."))
                } else {
                    plugin.logger.log(Level.INFO, "PortalEnterListener: portal teleport succeeded to ${world.name} for $pInfo")
                }
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "PortalEnterListener: Portal handling failed for $pInfo", e)
                try {
                    player.sendMessage(Component.text("Teleport failed (see server logs)."))
                } catch (e2: Exception) {
                    plugin.logger.log(Level.FINE, "PortalEnterListener: failed to send failure message to $pInfo", e2)
                }
            }
        }
    }
}
