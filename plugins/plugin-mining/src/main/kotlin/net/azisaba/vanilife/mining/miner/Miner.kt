package net.azisaba.vanilife.mining.miner

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

fun interface Miner {
    suspend fun perform(context: Context, plugin: Plugin)

    companion object Builtins {
        fun range(halfRange: Int): Miner = Miner { context, plugin ->
            fun breakBlock(block: Block) {
                if (block.type.isAir) return

                if (context.pickaxe != null) {
                    context.pickaxe.damage(1, context.player)
                    block.breakNaturally(context.pickaxe, true, true)
                } else {
                    block.type = Material.AIR
                }
            }

            plugin.launch(plugin.regionDispatcher(context.source.location)) {
                for (dx in -halfRange..halfRange) {
                    for (dy in -halfRange..halfRange) {
                        for (dz in -halfRange..halfRange) {
                            val currentLocation = context.source.location.add(dx.toDouble(), dy.toDouble(), dz.toDouble())

                            if (Bukkit.isOwnedByCurrentRegion(currentLocation)) {
                                breakBlock(currentLocation.block)
                            } else {
                                withContext(plugin.regionDispatcher(currentLocation)) {
                                    breakBlock(currentLocation.block)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    data class Context(val player: Player, val source: Block, val pickaxe: ItemStack?)
}
