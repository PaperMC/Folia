package net.azisaba.vanilife.mining.miner

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kotlinx.coroutines.withContext
import net.azisaba.vanilife.mining.MiningBlockTypeTags
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
            plugin.launch(plugin.regionDispatcher(context.source.location)) {
                for (dx in -halfRange..halfRange) {
                    for (dy in -halfRange..halfRange) {
                        for (dz in -halfRange..halfRange) {
                            val currentLocation = context.source.location.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble())

                            if (Bukkit.isOwnedByCurrentRegion(currentLocation)) {
                                breakBlockWithCheck(currentLocation.block, context)
                            } else {
                                withContext(plugin.regionDispatcher(currentLocation)) {
                                    breakBlockWithCheck(currentLocation.block, context)
                                }
                            }
                        }
                    }
                }
            }
        }

        fun vertical(up: Int, down: Int): Miner = Miner { context, plugin ->
            plugin.launch(plugin.regionDispatcher(context.source.location)) {
                for (dy in -down..up) {
                    val currentLocation = context.source.location.clone().add(0.0, dy.toDouble(), 0.0)

                    if (Bukkit.isOwnedByCurrentRegion(currentLocation)) {
                        breakBlockWithCheck(currentLocation.block, context)
                    } else {
                        withContext(plugin.regionDispatcher(currentLocation)) {
                            breakBlockWithCheck(currentLocation.block, context)
                        }
                    }
                }
            }
        }

        fun vein(maxBlocks: Int): Miner = VeinMiner(maxBlocks)

        internal fun isMinable(block: Block): Boolean = RegistryAccess.registryAccess()
            .getRegistry(RegistryKey.BLOCK)
            .getTag(MiningBlockTypeTags.MINER_MINABLE)
            .contains(block.type.asBlockType()!!.key())

        internal fun breakBlockWithCheck(block: Block, context: Context) {
            if (!isMinable(block)) return
            if (context.pickaxe != null) {
                context.pickaxe.damage(1, context.player)
                block.breakNaturally(context.pickaxe, true, true)
            } else {
                block.type = Material.AIR
            }
        }
    }

    data class Context(val player: Player, val source: Block, val pickaxe: ItemStack?)
}
