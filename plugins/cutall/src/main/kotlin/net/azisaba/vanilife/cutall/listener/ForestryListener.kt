package net.azisaba.vanilife.cutall.listener

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kotlinx.coroutines.withContext
import net.azisaba.vanilife.cutall.finder.TreeFinderRouter
import org.bukkit.Bukkit
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

            detected.trunkBlocks.forEach { trunkBlock ->
                if (Bukkit.isOwnedByCurrentRegion(trunkBlock)) trunkBlock.breakNaturally() else {
                    withContext(plugin.regionDispatcher(trunkBlock.location)) {
                        trunkBlock.breakNaturally()
                    }
                }
            }

            detected.leavesBlocks.forEach { leavesBlock ->
                if (Bukkit.isOwnedByCurrentRegion(leavesBlock)) leavesBlock.breakNaturally() else {
                    withContext(plugin.regionDispatcher(leavesBlock.location)) {
                        leavesBlock.breakNaturally()
                    }
                }
            }
        }
    }
}
