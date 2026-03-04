package net.azisaba.vanilife.forestry.finder

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.type.Leaves
import org.bukkit.plugin.Plugin
import org.joml.Vector3i
import org.joml.Vector3ic
import kotlin.math.abs

abstract class TreeFinder(private val plugin: Plugin) {
    suspend fun find(start: Block): DetectedTree? = withContext(plugin.regionDispatcher(start.location)) {
        val (trunkBlocks, leavesBlocks) = collectBlocks(start)
        if (leavesBlocks.isEmpty()) {
            return@withContext null
        }

        val expandedLeavesBlocks = leavesBlocks + expandLeaves(start, leavesBlocks)
        if (trunkBlocks.isEmpty() || expandedLeavesBlocks.isEmpty()) {
            return@withContext null
        }

        DetectedTree(trunkBlocks, expandedLeavesBlocks)
    }

    abstract fun isTrunkBlock(source: Block, block: Block): Boolean

    abstract fun isLeavesBlock(source: Block, block: Block): Boolean

    protected abstract fun isOverTrunkLimit(trunkBlocks: Set<Block>): Boolean

    protected abstract fun isOverLeavesLimit(leavesBlocks: Set<Block>): Boolean

    protected open suspend fun collectBlocks(start: Block): Pair<Set<Block>, Set<Block>> {
        val trunkSet = mutableSetOf<Block>()
        val leavesSet = mutableSetOf<Block>()

        val queue = ArrayDeque<Block>()
        val visitedBlocks = mutableSetOf<Block>()

        queue.add(start)

        while (queue.isNotEmpty()) {
            val currentBlock = queue.removeFirst()
            if (!visitedBlocks.add(currentBlock)) continue
            if (!isTrunkBlock(start, currentBlock) && currentBlock != start) continue

            trunkSet.add(currentBlock)

            if (isOverTrunkLimit(trunkSet) || isOverLeavesLimit(leavesSet)) {
                return emptySet<Block>() to emptySet()
            }

            for (adjacentBlock in fetchAdjacentBlocks(currentBlock)) {
                if (isTrunkBlock(start, adjacentBlock)) {
                    queue.add(adjacentBlock)
                } else if (isLeavesBlock(start, adjacentBlock)) {
                    leavesSet.add(adjacentBlock)
                }
            }
        }

        return trunkSet.toSet() to leavesSet.toSet()
    }

    protected open suspend fun expandLeaves(start: Block, sourceBlocks: Set<Block>): Set<Block> {
        val resultSet = mutableSetOf<Block>()

        val queue = ArrayDeque(sourceBlocks)
        val visitedBlocks = mutableSetOf<Block>()

        while (queue.isNotEmpty()) {
            val currentBlock = queue.removeFirst()

            if (isOverLeavesLimit(resultSet)) return emptySet()

            for (adjacentBlock in fetchAdjacentBlocks(currentBlock)) {
                if (adjacentBlock in visitedBlocks || !isLeavesBlock(start, adjacentBlock)) continue

                resultSet.add(adjacentBlock)

                visitedBlocks.add(adjacentBlock)
                queue.add(adjacentBlock)
            }
        }

        return resultSet.toSet()
    }

    protected suspend fun fetchBlockAt(location: Location): Block =
        if (Bukkit.isOwnedByCurrentRegion(location)) location.block else {
            withContext(plugin.regionDispatcher(location)) {
                location.block
            }
        }

    protected open suspend fun fetchAdjacentBlocks(block: Block): Set<Block> = buildSet {
        for (offset in ADJACENT_OFFSETS) {
            val location = block.location.add(offset.x().toDouble(), offset.y().toDouble(), offset.z().toDouble())
            val block = fetchBlockAt(location)
            add(block)
        }
    }

    companion object {
        private val ADJACENT_OFFSETS: Array<Vector3ic> = arrayOf(
            Vector3i(0, 1, 0), Vector3i(0, -1, 0),
            Vector3i(0, 0, -1), Vector3i(0, 0, 1),
            Vector3i(1, 0, 0), Vector3i(-1, 0, 0),
        )

        fun oak(plugin: Plugin): TreeFinder = SimpleTreeFinder(Material.OAK_LOG, Material.OAK_LEAVES, 240, 450, 3, plugin)

        fun fancyOak(plugin: Plugin): TreeFinder = FancyOakTreeFinder(plugin)

        fun spruce(plugin: Plugin): TreeFinder =
            SimpleTreeFinder(Material.SPRUCE_LOG, Material.SPRUCE_LEAVES, 240, 450, 3, plugin)

        fun birch(plugin: Plugin): TreeFinder =
            SimpleTreeFinder(Material.BIRCH_LOG, Material.BIRCH_LEAVES, 240, 450, 3, plugin)

        fun jungle(plugin: Plugin): TreeFinder =
            SimpleTreeFinder(Material.JUNGLE_LOG, Material.JUNGLE_LEAVES, 640, 720, 8, plugin)

        fun acacia(plugin: Plugin): TreeFinder =
            SimpleTreeFinder(Material.ACACIA_LOG, Material.ACACIA_LEAVES, 240, 450, 6, plugin)

        fun darkOak(plugin: Plugin): TreeFinder =
            SimpleTreeFinder(Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES, 240, 450, 6, plugin)

        fun paleOak(plugin: Plugin): TreeFinder =
            SimpleTreeFinder(Material.PALE_OAK_LOG, Material.PALE_OAK_LEAVES, 240, 450, 6, plugin)
    }
}

private class SimpleTreeFinder(
    private val trunk: Material, private val leaves: Material,
    private val maxTrunkBlocks: Int, private val maxLeavesBlocks: Int,
    private val maxDeltaXZ: Int,
    plugin: Plugin,
) : TreeFinder(plugin) {
    override fun isTrunkBlock(source: Block, block: Block): Boolean =
        isWithinBounds(source, block) && block.type == trunk

    override fun isLeavesBlock(source: Block, block: Block): Boolean =
        isWithinBounds(source, block) && block.type == leaves && (block.blockData as? Leaves)?.isPersistent == false

    override fun isOverTrunkLimit(trunkBlocks: Set<Block>): Boolean = trunkBlocks.size > maxTrunkBlocks

    override fun isOverLeavesLimit(leavesBlocks: Set<Block>): Boolean = leavesBlocks.size > maxLeavesBlocks

    private fun isWithinBounds(source: Block, block: Block): Boolean =
        abs(block.x - source.x) <= maxDeltaXZ && abs(block.z - source.z) <= maxDeltaXZ
}

private class FancyOakTreeFinder(plugin: Plugin) : TreeFinder(plugin) {
    override fun isTrunkBlock(source: Block, block: Block): Boolean = block.type == Material.OAK_LOG

    override fun isLeavesBlock(source: Block, block: Block): Boolean =
        block.type == Material.OAK_LEAVES && (block.blockData as? Leaves)?.isPersistent == false

    override fun isOverTrunkLimit(trunkBlocks: Set<Block>): Boolean = trunkBlocks.size > 360

    override fun isOverLeavesLimit(leavesBlocks: Set<Block>): Boolean = leavesBlocks.size > 560

    override suspend fun fetchAdjacentBlocks(block: Block): Set<Block> = buildSet {
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    add(fetchBlockAt(block.location.add(dx.toDouble(), dy.toDouble(), dz.toDouble())))
                }
            }
        }
    }
}
