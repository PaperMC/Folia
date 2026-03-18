package net.azisaba.vanilife.forestry.finder

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.block.BlockState
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

data class DetectedTree(
    val trunkBlocks: Collection<BlockState>, val leafBlocks: Collection<BlockState>,
) : Iterable<BlockState> {
    override fun iterator(): Iterator<BlockState> = (trunkBlocks + leafBlocks).iterator()

    suspend fun dropItems(tool: ItemStack, player: Player, plugin: Plugin) {
        val root = trunkBlocks.minByOrNull(BlockState::getY)?.location?.add(0.5, 0.5, 0.5) ?: return

        withContext(plugin.regionDispatcher(root)) {
            val drops = flatMap { block ->
                if (!Bukkit.isOwnedByCurrentRegion(block.location)) {
                    withContext(plugin.regionDispatcher(block.location)) {
                        block.getDrops(tool, player)
                    }
                } else block.getDrops(tool, player)
            }.compacted()

            tool.damage(drops.size, player)

            drops.forEach { drop ->
                root.world.dropItemNaturally(root, drop)
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
