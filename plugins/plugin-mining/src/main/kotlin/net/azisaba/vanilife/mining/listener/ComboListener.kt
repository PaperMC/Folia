package net.azisaba.vanilife.mining.listener

import net.azisaba.vanilife.mining.combo.ComboCounter
import net.azisaba.vanilife.mining.combo.ComboCounterSource
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerQuitEvent

internal object ComboListener : Listener {
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (ComboCounter.shouldIncrementCombo(event.block)) {
            val counter = ComboCounterSource.getOrCreate(event.player)
            counter.increment()
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        ComboCounterSource.remove(event.player)
    }
}
