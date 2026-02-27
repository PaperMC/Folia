package net.azisaba.vanilife.housing.waves.flotsam

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import kotlinx.coroutines.withContext
import net.azisaba.vanilife.housing.waves.WavePos
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.plugin.Plugin
import org.joml.Vector3i
import org.joml.Vector3ic
import kotlin.random.Random

class AsyncLandFinder(
    private val plugin: Plugin,
    private val world: World,
    private val selectorRandom: Random,
    private val offsetRadiusX: Int = 6,
    private val offsetRadiusY: Int = 3,
    private val offsetRadiusZ: Int = 6,
    private val maxCandidates: Int = 12,
) {
    suspend fun find(wavePos: WavePos): Location? {
        val start = SpigotConversionUtil.toBukkitLocation(world, wavePos.location())
        return withContext(plugin.regionDispatcher(start)) {
            val candidates = ArrayList<Location>(maxCandidates)
            val offsets = shuffledOffsets()
            for (offset in offsets) {
                val x = start.x() + offset.x()
                val y = start.y() + offset.y()
                val z = start.z() + offset.z()
                val location = Location(world, x, y, z).toBlockLocation()
                val land = findLandAt(location) ?: continue
                candidates += land
                if (candidates.size >= maxCandidates) break
            }
            if (candidates.isEmpty()) return@withContext null
            return@withContext candidates[selectorRandom.nextInt(candidates.size)]
        }
    }

    private suspend fun findLandAt(location: Location): Location? {
        return if (Bukkit.isOwnedByCurrentRegion(location)) {
            testLandAt(location)
        } else {
            withContext(plugin.regionDispatcher(location)) { testLandAt(location) }
        }
    }

    private fun testLandAt(location: Location): Location? {
        val block = world.getBlockAt(location)
        val isLandBlock = block.type == Material.SAND && block.getRelative(BlockFace.UP).type.isAir
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
        result.shuffle(selectorRandom)
        return result
    }
}
