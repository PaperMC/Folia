package net.azisaba.vanilife.forestry.finder

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.type.Leaves
import org.bukkit.plugin.Plugin
import org.joml.Vector3i
import org.joml.Vector3ic
import kotlin.math.abs

abstract class TreeFinder(private val plugin: Plugin) {
    suspend fun find(start: BlockState): DetectedTree? = withContext(plugin.regionDispatcher(start.location)) {
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

    abstract fun isTrunkBlock(source: BlockState, blockState: BlockState): Boolean

    abstract fun isLeavesBlock(source: BlockState, blockState: BlockState): Boolean

    protected abstract fun isOverTrunkLimit(trunkBlocks: Set<BlockState>): Boolean

    protected abstract fun isOverLeavesLimit(leavesBlocks: Set<BlockState>): Boolean

    protected open suspend fun collectBlocks(start: BlockState): Pair<Set<BlockState>, Set<BlockState>> {
        val trunkSet = mutableSetOf<BlockState>()
        val leavesSet = mutableSetOf<BlockState>()

        val queue = ArrayDeque<BlockState>()
        val visitedBlocks = mutableSetOf<BlockState>()

        queue.add(start)

        while (queue.isNotEmpty()) {
            val currentBlock = queue.removeFirst()
            if (!visitedBlocks.add(currentBlock)) continue
            if (!isTrunkBlock(start, currentBlock) && currentBlock != start) continue

            trunkSet.add(currentBlock)

            if (isOverTrunkLimit(trunkSet) || isOverLeavesLimit(leavesSet)) {
                return emptySet<BlockState>() to emptySet()
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

    protected open suspend fun expandLeaves(start: BlockState, sourceBlocks: Set<BlockState>): Set<BlockState> {
        val resultSet = mutableSetOf<BlockState>()

        val queue = ArrayDeque(sourceBlocks)
        val visitedBlocks = mutableSetOf<BlockState>()

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

    protected suspend fun fetchBlockAt(location: Location): BlockState =
        if (Bukkit.isOwnedByCurrentRegion(location)) location.block.state else {
            withContext(plugin.regionDispatcher(location)) {
                location.block.state
            }
        }

    protected open suspend fun fetchAdjacentBlocks(block: BlockState): Set<BlockState> = buildSet {
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

        fun oak(plugin: Plugin): TreeFinder =
            SimpleTreeFinder(Material.OAK_LOG, Material.OAK_LEAVES, 240, 450, 3, plugin)

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
    override fun isTrunkBlock(source: BlockState, blockState: BlockState): Boolean =
        isWithinBounds(source, blockState) && blockState.type == trunk

    override fun isLeavesBlock(source: BlockState, blockState: BlockState): Boolean =
        isWithinBounds(source, blockState) && blockState.type == leaves && (blockState.blockData as? Leaves)?.isPersistent == false

    override fun isOverTrunkLimit(trunkBlocks: Set<BlockState>): Boolean = trunkBlocks.size > maxTrunkBlocks

    override fun isOverLeavesLimit(leavesBlocks: Set<BlockState>): Boolean = leavesBlocks.size > maxLeavesBlocks

    private fun isWithinBounds(source: BlockState, block: BlockState): Boolean =
        abs(block.x - source.x) <= maxDeltaXZ && abs(block.z - source.z) <= maxDeltaXZ
}

private class FancyOakTreeFinder(plugin: Plugin) : TreeFinder(plugin) {
    override fun isTrunkBlock(source: BlockState, blockState: BlockState): Boolean = blockState.type == Material.OAK_LOG

    override fun isLeavesBlock(source: BlockState, blockState: BlockState): Boolean =
        blockState.type == Material.OAK_LEAVES && (blockState.blockData as? Leaves)?.isPersistent == false

    override fun isOverTrunkLimit(trunkBlocks: Set<BlockState>): Boolean = trunkBlocks.size > 360

    override fun isOverLeavesLimit(leavesBlocks: Set<BlockState>): Boolean = leavesBlocks.size > 560

    override suspend fun fetchAdjacentBlocks(block: BlockState): Set<BlockState> = buildSet {
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
