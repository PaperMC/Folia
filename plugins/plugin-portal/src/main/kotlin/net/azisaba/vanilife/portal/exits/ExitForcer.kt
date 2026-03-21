package net.azisaba.vanilife.portal.exits

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kotlinx.coroutines.withContext
import net.azisaba.vanilife.islands.IslandPos
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.plugin.Plugin
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class ExitForcer(
    val world: World,
    private val baseRadius: Int,
    private val radiusVariance: Int,
    private val resourceCellSpacing: Int,
    private val safeSearchRadius: Int,
) {
    suspend fun findSafeLocation(islandPos: IslandPos, plugin: Plugin): Location {
        val (baseX, baseZ) = baseXZOf(islandPos)

        return withContext(plugin.regionDispatcher(Location(world, baseX.toDouble(), 0.0, baseZ.toDouble()))) {
            for (radius in 0..safeSearchRadius) {
                for (dx in -radius..radius) {
                    for (dz in -radius..radius) {
                        val x = baseX + dx
                        val z = baseZ + dz

                        val xzLocation = Location(world, x.toDouble(), 0.0, z.toDouble())

                        val y = if (!Bukkit.isOwnedByCurrentRegion(xzLocation)) {
                            withContext(plugin.regionDispatcher(xzLocation)) {
                                safeYOf(x, z)
                            }
                        } else safeYOf(x, z)

                        if (y != null) {
                            return@withContext Location(world, x + 0.5, y.toDouble(), z + 0.5)
                        }
                    }
                }
            }

            return@withContext Location(
                world,
                baseX.toDouble(),
                world.getHighestBlockYAt(baseX, baseZ, HeightMap.RESOURCE_OVERWORLD_WORLD_SURFACE).toDouble(),
                baseZ.toDouble(),
            )
        }
    }

    private fun baseXZOf(islandPos: IslandPos): Pair<Int, Int> {
        val random = Random(islandPos.computeSeed(world.seed))
        val radius = baseRadius + random.nextDouble() * radiusVariance
        val angle = random.nextDouble(0.0, PI * 2.0)

        val originalX = islandPos.x() * resourceCellSpacing
        val originalZ = islandPos.z() * resourceCellSpacing
        val x = originalX + (cos(angle) * radius).roundToInt()
        val z = originalZ + (sin(angle) * radius).roundToInt()

        return x to z
    }

    private fun safeYOf(x: Int, z: Int): Int? {
        val surfaceY = world.getHighestBlockYAt(x, z, HeightMap.RESOURCE_OVERWORLD_WORLD_SURFACE) + 1
        val block = world.getBlockAt(x, surfaceY, z)
        return if (ExitSafetyRule.test(block)) surfaceY else null
    }
}