package net.azisaba.vanilife.islands.waves

import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.util.Vector3d
import net.azisaba.vanilife.islands.CoastSide
import net.azisaba.vanilife.islands.IslandDefaults
import net.azisaba.vanilife.islands.IslandPos
import net.azisaba.vanilife.islands.boundaryBlock

data class WavePos(val islandPos: IslandPos, val coastSide: CoastSide, val index: Int) {
    fun edgeCoord(): Int = islandPos.boundaryBlock(coastSide)

    fun location(): Location {
        val lateralStart = if (coastSide.axisX) islandPos.minBlockZ().toDouble() else islandPos.minBlockX().toDouble()
        val lateralEnd = if (coastSide.axisX) islandPos.maxBlockZ().toDouble() else islandPos.maxBlockX().toDouble()
        val lateralStep = (lateralEnd - lateralStart) / (WAVES_PER_COAST_SIDE - 1).toDouble()
        val lateral = lateralStart + lateralStep * index
        val x = if (coastSide.axisX) edgeCoord().toDouble() else lateral
        val z = if (coastSide.axisX) lateral else edgeCoord().toDouble()
        return Location(x, IslandDefaults.SEA_LEVEL + 0.15, z, coastSide.yaw, 0f)
    }

    fun computeForward(location: Location, offset: Double): Location {
        val forwardDir = -coastSide.coastNormalSign
        val x = if (coastSide.axisX) location.x + forwardDir * offset else location.x
        val z = if (coastSide.axisX) location.z else location.z + forwardDir * offset
        return Location(Vector3d(x, location.y, z), location.yaw, location.pitch)
    }

    fun computeSeed(): Long {
        var seed = (islandPos.x().toLong() * 73856093L) xor (islandPos.z().toLong() * 19349663L)
        seed = seed xor (index.toLong() * 83492791L)
        seed = seed xor (coastSide.ordinal.toLong() * 29791L)
        return seed
    }

    companion object {
        const val WAVES_PER_COAST_SIDE = 15
        const val WAVES_PER_ISLAND = WAVES_PER_COAST_SIDE * 4

        fun posSet(islandPos: IslandPos): Set<WavePos> = buildSet(WAVES_PER_COAST_SIDE * CoastSide.entries.size) {
            for (coastSide in CoastSide.entries) {
                for (index in 0 until WAVES_PER_COAST_SIDE) {
                    add(WavePos(islandPos, coastSide, index))
                }
            }
        }
    }
}
