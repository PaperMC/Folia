package net.azisaba.vanilife.mining.listener

import net.azisaba.vanilife.event.BlockDropLootEvent
import net.azisaba.vanilife.mining.FrozenOre
import net.coreprotect.CoreProtectAPI
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack

internal class FrozenOreMiningListener(private val coreProtectApi: CoreProtectAPI?) : Listener {
    @EventHandler
    fun onBlockDropLoot(event: BlockDropLootEvent) {
        val frozenOre = FrozenOre.frozenOreOf(event.blockState, coreProtectApi) ?: return
        event.editDrops { drops ->
            drops.map { drop ->
                if (drop.type == frozenOre.base) {
                    ItemStack.of(frozenOre.item, drop.amount)
                } else drop
            }
        }
    }
}
