package net.azisaba.vanilife.islands.wrack

import io.papermc.paper.math.Position
import net.azisaba.vanilife.islands.CoastSide
import net.azisaba.vanilife.islands.IslandDefaults
import net.azisaba.vanilife.islands.IslandPos
import net.azisaba.vanilife.islands.boundaryBlock
import org.bukkit.World
import org.bukkit.plugin.Plugin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

internal data class DriftPath(val startPos: Position, val endPos: Position, val random: Random) {
    private val horizontalAmplitude: Double = 6.0
    private val verticalAmplitude: Double = 2.0

    private val frequency: Double = 2.0

    private val phase: Double = random.nextDouble(0.0, PI * 2)

    fun computePos(progress: Double): Position {
        val t = progress.coerceIn(0.0, 1.0)

        val baseX = lerp(startPos.x(), endPos.x(), t)
        val baseY = lerp(startPos.y(), endPos.y(), t)
        val baseZ = lerp(startPos.z(), endPos.z(), t)

        val dirX = endPos.x() - startPos.x()
        val dirZ = endPos.z() - startPos.z()
        val length = hypot(dirX, dirZ)

        if (length == 0.0) return startPos

        val normDirX = dirX / length
        val normDirZ = dirZ / length

        val orthoX = -normDirZ
        val orthoZ = normDirX

        val envelope = sin(t * PI)

        val horizontalWave = sin(t * PI * frequency + phase)
        val horizontalOffset = horizontalWave * horizontalAmplitude * envelope

        val verticalWave = sin(t * PI * (frequency * 0.7) + phase * 0.5)
        val verticalOffset = -abs(verticalWave) * verticalAmplitude * envelope

        val finalX = baseX + orthoX * horizontalOffset
        val finalZ = baseZ + orthoZ * horizontalOffset
        val finalY = (baseY + verticalOffset).coerceAtMost(IslandDefaults.SEA_LEVEL.toDouble() + 0.05)

        return Position.fine(finalX, finalY, finalZ)
    }

    private fun lerp(a: Double, b: Double, t: Double): Double {
        return a + (b - a) * t
    }

    companion object {
        suspend fun random(islandPos: IslandPos, coastSide: CoastSide, world: World, plugin: Plugin): DriftPath {
            val salt = System.nanoTime()
            val random = Random(islandPos.computeSeed(world.seed) xor coastSide.ordinal.toLong() xor salt)

            val landFinder = LandFinder(random)

            val seaLevel = IslandDefaults.SEA_LEVEL.toDouble()

            val minX = islandPos.minBlockX().toDouble()
            val maxX = islandPos.maxBlockX().toDouble()
            val minZ = islandPos.minBlockZ().toDouble()
            val maxZ = islandPos.maxBlockZ().toDouble()

            val boundary = islandPos.boundaryBlock(coastSide).toDouble()
            val rawEndX = if (coastSide.axisX) boundary else random.nextDouble(minX, maxX)
            val rawEndZ = if (coastSide.axisZ) boundary else random.nextDouble(minZ, maxZ)
            val rawEndPos = Position.fine(rawEndX, seaLevel, rawEndZ)
            val finalEndPos = landFinder.find(world, rawEndPos, plugin) ?: rawEndPos

            val normX = if (coastSide.axisX) coastSide.coastNormalSign else 0.0
            val normZ = if (coastSide.axisZ) coastSide.coastNormalSign else 0.0

            val tanX = if (coastSide.axisZ) 1.0 else 0.0
            val tanZ = if (coastSide.axisX) 1.0 else 0.0

            val seaOffset = IslandDefaults.GRID_SIZE * (0.5 + random.nextDouble())

            val startX = finalEndPos.x() + normX * seaOffset + tanX * random.nextDouble(
                -IslandDefaults.GRID_SIZE.toDouble(),
                IslandDefaults.GRID_SIZE.toDouble()
            )
            val startZ = finalEndPos.z() + normZ * seaOffset + tanZ * random.nextDouble(
                -IslandDefaults.GRID_SIZE.toDouble(),
                IslandDefaults.GRID_SIZE.toDouble()
            )

            return DriftPath(
                Position.fine(startX, seaLevel, startZ),
                finalEndPos,
                random
            )
        }
    }
}
