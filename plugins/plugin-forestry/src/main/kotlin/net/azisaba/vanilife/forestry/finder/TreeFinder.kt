package net.azisaba.vanilife.forestry.finder

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockState
import org.bukkit.block.data.type.Leaves
import org.bukkit.plugin.Plugin
import org.joml.Vector3i
import org.joml.Vector3ic
import kotlin.math.abs

sealed interface TreeFinder {
    suspend fun find(start: BlockState, plugin: Plugin): DetectedTree?

    companion object Builtins {
        val COMPOSITE_DEFAULT: Composite = composite(oak(), spruce(), birch(), jungle(), acacia(), darkOak(), paleOak())

        fun oak(): Single = AllAdjacent(Material.OAK_LOG, Material.OAK_LEAVES, 240, 450, 3)

        fun spruce(): Single = FaceAdjacent(Material.SPRUCE_LOG, Material.SPRUCE_LEAVES, 240, 450, 3)

        fun birch(): Single = FaceAdjacent(Material.BIRCH_LOG, Material.BIRCH_LEAVES, 240, 450, 3)

        fun jungle(): Single = FaceAdjacent(Material.JUNGLE_LOG, Material.JUNGLE_LEAVES, 640, 720, 8)

        fun acacia(): Single = FaceAdjacent(Material.ACACIA_LOG, Material.ACACIA_LEAVES, 240, 450, 6)

        fun darkOak(): Single = FaceAdjacent(Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES, 240, 450, 6)

        fun paleOak(): Single = FaceAdjacent(Material.PALE_OAK_LOG, Material.PALE_OAK_LEAVES, 240, 450, 6)

        fun composite(vararg finders: Single): Composite = Composite(finders.toList())
    }

    abstract class Single(
        protected val trunk: Material, protected val leaves: Material,
        protected val maxTrunkBlocks: Int, protected val maxLeavesBlocks: Int,
        protected val maxDeltaXZ: Int,
    ) : TreeFinder {
        fun isTrunkBlock(source: BlockState, blockState: BlockState): Boolean =
            blockState.type == trunk && isWithinBounds(source, blockState)

        fun isLeavesBlock(source: BlockState, blockState: BlockState): Boolean = blockState.type == leaves &&
                (blockState !is Leaves || !blockState.isPersistent) &&
                isWithinBounds(source, blockState)

        fun isWithinBounds(source: BlockState, block: BlockState): Boolean =
            abs(block.x - source.x) <= maxDeltaXZ && abs(block.z - source.z) <= maxDeltaXZ

        override suspend fun find(start: BlockState, plugin: Plugin): DetectedTree? =
            withContext(plugin.regionDispatcher(start.location)) {
                val (trunkBlocks, leavesBlocks) = collectBlocks(start, plugin)
                if (leavesBlocks.isEmpty()) {
                    return@withContext null
                }

                val expandedLeavesBlocks = leavesBlocks + expandLeaves(start, leavesBlocks, plugin)
                if (trunkBlocks.isEmpty() || expandedLeavesBlocks.isEmpty()) {
                    return@withContext null
                }

                DetectedTree(trunkBlocks, expandedLeavesBlocks)
            }

        protected suspend fun fetchBlockAt(location: Location, plugin: Plugin): BlockState =
            if (Bukkit.isOwnedByCurrentRegion(location)) location.block.state else {
                withContext(plugin.regionDispatcher(location)) {
                    location.block.state
                }
            }

        protected abstract suspend fun fetchAdjacentBlocks(block: BlockState, plugin: Plugin): Set<BlockState>

        private suspend fun collectBlocks(start: BlockState, plugin: Plugin): Pair<Set<BlockState>, Set<BlockState>> {
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

                if (trunkSet.size > maxTrunkBlocks || leavesSet.size > maxLeavesBlocks) {
                    return emptySet<BlockState>() to emptySet()
                }

                for (adjacentBlock in fetchAdjacentBlocks(currentBlock, plugin)) {
                    if (isTrunkBlock(start, adjacentBlock)) {
                        queue.add(adjacentBlock)
                    } else if (isLeavesBlock(start, adjacentBlock)) {
                        leavesSet.add(adjacentBlock)
                    }
                }
            }

            return trunkSet.toSet() to leavesSet.toSet()
        }

        private suspend fun expandLeaves(
            start: BlockState,
            sourceBlocks: Set<BlockState>,
            plugin: Plugin,
        ): Set<BlockState> {
            val resultSet = mutableSetOf<BlockState>()

            val queue = ArrayDeque(sourceBlocks)
            val visitedBlocks = mutableSetOf<BlockState>()

            while (queue.isNotEmpty()) {
                val currentBlock = queue.removeFirst()

                if (resultSet.size > maxLeavesBlocks) return emptySet()

                for (adjacentBlock in fetchAdjacentBlocks(currentBlock, plugin)) {
                    if (adjacentBlock in visitedBlocks || !isLeavesBlock(start, adjacentBlock)) {
                        continue
                    }

                    resultSet.add(adjacentBlock)

                    visitedBlocks.add(adjacentBlock)
                    queue.add(adjacentBlock)
                }
            }

            return resultSet.toSet()
        }
    }

    class FaceAdjacent(
        trunk: Material,
        leaves: Material,
        maxTrunkBlocks: Int,
        maxLeavesBlocks: Int,
        maxDeltaXZ: Int,
    ) : Single(trunk, leaves, maxTrunkBlocks, maxLeavesBlocks, maxDeltaXZ) {
        override suspend fun fetchAdjacentBlocks(block: BlockState, plugin: Plugin): Set<BlockState> = buildSet {
            for (offset in OFFSETS) {
                val location = block.location.add(offset.x().toDouble(), offset.y().toDouble(), offset.z().toDouble())
                val block = fetchBlockAt(location, plugin)
                add(block)
            }
        }

        private companion object {
            val OFFSETS: Array<Vector3ic> = arrayOf(
                Vector3i(0, 1, 0), Vector3i(0, -1, 0),
                Vector3i(0, 0, -1), Vector3i(0, 0, 1),
                Vector3i(1, 0, 0), Vector3i(-1, 0, 0),
            )
        }
    }

    class AllAdjacent(
        trunk: Material,
        leaves: Material,
        maxTrunkBlocks: Int,
        maxLeavesBlocks: Int,
        maxDeltaXZ: Int,
    ) : Single(trunk, leaves, maxTrunkBlocks, maxLeavesBlocks, maxDeltaXZ) {
        override suspend fun fetchAdjacentBlocks(block: BlockState, plugin: Plugin): Set<BlockState> = buildSet {
            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        add(fetchBlockAt(block.location.add(dx.toDouble(), dy.toDouble(), dz.toDouble()), plugin))
                    }
                }
            }
        }
    }

    class Composite(val finders: List<Single>) : TreeFinder {
        override suspend fun find(start: BlockState, plugin: Plugin): DetectedTree? =
            filterApplicableFinders(start).firstNotNullOfOrNull { it.find(start, plugin) }

        fun filterApplicableFinders(start: BlockState): List<Single> = finders.filter {
            it.isTrunkBlock(start, start)
        }
    }
}