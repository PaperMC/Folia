package net.azisaba.vanilife.portal.listener

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.portal.exits.ExitAnchor
import net.azisaba.vanilife.portal.exits.setExitAnchor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

internal object ExitAnchorListener : Listener {
    @EventHandler
    fun onPlayerSetSpawn(event: PlayerSetSpawnEvent) {
        val location = event.location?.takeIf { it.world == Vanilife.getResourceWorld() } ?: return

        val exitAnchorType = when (event.cause) {
            PlayerSetSpawnEvent.Cause.BED -> ExitAnchor.Type.BED
            PlayerSetSpawnEvent.Cause.RESPAWN_ANCHOR -> ExitAnchor.Type.RESPAWN_ANCHOR
            else -> return
        }

        location.world.setExitAnchor(event.player, exitAnchorType, location.toBlock())
    }
}
