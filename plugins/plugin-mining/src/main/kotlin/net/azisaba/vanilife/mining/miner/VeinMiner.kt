package net.azisaba.vanilife.mining.miner

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import java.util.ArrayDeque

internal data class VeinMiner(private val maxBlocks: Int, private val targetType: Material) : Miner {
    override suspend fun perform(context: Miner.Context, plugin: Plugin) {
        if (maxBlocks <= 0) return

        plugin.launch(plugin.regionDispatcher(context.source.location)) {
            performVeinMining(context, plugin)
        }
    }

    private suspend fun performVeinMining(context: Miner.Context, plugin: Plugin) {
        val sourceLocation = context.source.location.toBlockLocation()
        val queue = ArrayDeque<Location>()
        val visited = mutableSetOf(blockKeyOf(sourceLocation))

        adjacentLocationsOf(sourceLocation).forEach(queue::addLast)

        var brokenBlocks = 0
        while (queue.isNotEmpty() && brokenBlocks < maxBlocks) {
            val current = queue.removeFirst()
            val currentKey = blockKeyOf(current)
            if (!visited.add(currentKey)) continue

            val adjacent = if (Bukkit.isOwnedByCurrentRegion(current)) {
                breakConnectedBlock(context, current)
            } else {
                withContext(plugin.regionDispatcher(current)) {
                    breakConnectedBlock(context, current)
                }
            } ?: continue

            brokenBlocks++
            adjacent.forEach { neighbor ->
                if (blockKeyOf(neighbor) !in visited) {
                    queue.addLast(neighbor)
                }
            }
        }
    }

    private fun breakConnectedBlock(context: Miner.Context, current: Location): List<Location>? {
        val block = current.block
        if (block.type != targetType || !Miner.isMinable(block)) return null

        Miner.breakBlockWithCheck(block, context)
        return adjacentLocationsOf(current)
    }

    private fun adjacentLocationsOf(current: Location): List<Location> = listOf(
        current.clone().add(1.0, 0.0, 0.0),
        current.clone().add(-1.0, 0.0, 0.0),
        current.clone().add(0.0, 1.0, 0.0),
        current.clone().add(0.0, -1.0, 0.0),
        current.clone().add(0.0, 0.0, 1.0),
        current.clone().add(0.0, 0.0, -1.0),
    )

    private fun blockKeyOf(location: Location): Triple<Int, Int, Int> =
        Triple(location.blockX, location.blockY, location.blockZ)
}
