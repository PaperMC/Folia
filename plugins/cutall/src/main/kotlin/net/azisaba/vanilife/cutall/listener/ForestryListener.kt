package net.azisaba.vanilife.cutall.listener

import net.azisaba.vanilife.cutall.cutdown.CutDownAnimator
import net.azisaba.vanilife.cutall.cutdown.CutDownContext
import net.azisaba.vanilife.cutall.finder.TreeFinderRouter
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.Plugin

internal class ForestryListener(private val plugin: Plugin, private val finderRouter: TreeFinderRouter) : Listener {
    @EventHandler
    suspend fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (player.gameMode.isInvulnerable) return

        val block = event.block
        if (finderRouter.findApplicableFinders(block).isNotEmpty()) {
            event.isCancelled = true
            val detected = finderRouter.find(block) ?: return

            val context = CutDownContext(player, block, detected)

            CutDownAnimator().animate(context)
        }
    }
}
