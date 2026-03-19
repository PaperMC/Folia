package net.azisaba.vanilife.forestry.finder

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.keys.tags.BlockTypeTagKeys
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

data class DetectedTree(
    val trunkBlocks: Collection<BlockState>, val leafBlocks: Collection<BlockState>, val sapling: Material,
) : Iterable<BlockState> {
    override fun iterator(): Iterator<BlockState> = (trunkBlocks + leafBlocks).iterator()

    suspend fun placeSapling(plugin: Plugin) {
        val lowerestPerColumn = trunkBlocks
            .groupBy { it.x to it.z }
            .mapNotNull { (_, column) -> column.minByOrNull(BlockState::getY) }
            .filter { blockState ->
                RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.BLOCK)
                    .getTag(BlockTypeTagKeys.DIRT)
                    .contains(blockState.block.getRelative(BlockFace.DOWN).type.asBlockType()!!.key())
            }

        if (lowerestPerColumn.isEmpty()) return

        withContext(plugin.regionDispatcher(lowerestPerColumn.first().location)) {
            for (blockState in lowerestPerColumn) {
                if (!Bukkit.isOwnedByCurrentRegion(blockState.location)) {
                    withContext(plugin.regionDispatcher(blockState.location)) {
                        blockState.block.type = sapling
                    }
                } else {
                    blockState.block.type = sapling
                }
            }
        }
    }

    suspend fun dropItems(tool: ItemStack, player: Player, plugin: Plugin) {
        val dropLocation = trunkBlocks.minByOrNull(BlockState::getY)?.location?.add(0.5, 0.5, 0.5) ?: return

        withContext(plugin.regionDispatcher(dropLocation)) {
            val drops = flatMap { block ->
                if (!Bukkit.isOwnedByCurrentRegion(block.location)) {
                    withContext(plugin.regionDispatcher(block.location)) {
                        block.getDrops(tool, player)
                    }
                } else block.getDrops(tool, player)
            }.compacted()

            tool.damage(drops.size, player)

            drops.forEach { drop ->
                dropLocation.world.dropItemNaturally(dropLocation, drop)
            }
        }
    }

    private fun List<ItemStack>.compacted(): List<ItemStack> = buildList {
        for (itemStack in this@compacted) {
            if (itemStack.isEmpty) continue

            var remaining = itemStack.amount

            for (existing in this) {
                if (!existing.isSimilar(itemStack)) continue

                val move = minOf(existing.maxStackSize - existing.amount, remaining)
                if (move <= 0) continue

                existing.amount += move
                remaining -= move
                if (remaining == 0) break
            }

            while (remaining > 0) {
                add(itemStack.clone().apply {
                    amount = minOf(maxStackSize, remaining)
                })
                remaining -= last().amount
            }
        }
    }
}
