package net.azisaba.vanilife.islands.wrack

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import io.papermc.paper.math.Position
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.plugin.Plugin
import org.joml.Vector3i
import org.joml.Vector3ic
import kotlin.random.Random

internal class LandFinder(
    private val random: Random,
    private val offsetRadiusX: Int = 6, private val offsetRadiusY: Int = 3, private val offsetRadiusZ: Int = 6,
    private val maxCandidates: Int = 12,
) {
    suspend fun find(world: World, pos: Position, plugin: Plugin): Location? {
        val start = Location(world, pos.x(), pos.y(), pos.z())
        return withContext(plugin.regionDispatcher(start)) {
            val candidates = ArrayList<Location>(maxCandidates)

            val offsets = shuffledOffsets()
            for (offset in offsets) {
                val x = start.x() + offset.x()
                val y = start.y() + offset.y()
                val z = start.z() + offset.z()
                val location = Location(world, x, y, z).toBlockLocation()
                val land = findLandAt(plugin, location) ?: continue
                candidates += land
                if (candidates.size >= maxCandidates) break
            }

            return@withContext if (candidates.isEmpty()) null else candidates[random.nextInt(candidates.size)]
        }
    }

    private suspend fun findLandAt(plugin: Plugin, location: Location): Location? {
        return if (Bukkit.isOwnedByCurrentRegion(location)) {
            testLandAt(location)
        } else {
            withContext(plugin.regionDispatcher(location)) { testLandAt(location) }
        }
    }

    private fun testLandAt(location: Location): Location? {
        val block = location.world.getBlockAt(location)
        val isLandBlock = block.type.isAir && block.getRelative(BlockFace.DOWN).type == Material.SAND
        return if (isLandBlock) location else null
    }

    private fun shuffledOffsets(): List<Vector3ic> {
        val result = ArrayList<Vector3ic>()
        for (dx in -offsetRadiusX..offsetRadiusX) {
            for (dz in -offsetRadiusZ..offsetRadiusZ) {
                for (dy in -offsetRadiusY..offsetRadiusY) {
                    result += Vector3i(dx, dy, dz)
                }
            }
        }
        return result.shuffled(random)
    }
}
