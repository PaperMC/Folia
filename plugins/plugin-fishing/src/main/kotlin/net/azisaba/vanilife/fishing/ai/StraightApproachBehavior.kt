package net.azisaba.vanilife.fishing.ai

import io.papermc.paper.math.FinePosition
import kotlin.math.sqrt

class StraightApproachBehavior(val configuration: Configuration = Configuration()) : FishBehavior.ApproachBehavior {
    override fun tick(time: Long, position: FinePosition, fishHookPosition: FinePosition): FinePosition? {
        val deltaX = fishHookPosition.x() - position.x()
        val deltaY = fishHookPosition.y() - position.y()
        val deltaZ = fishHookPosition.z() - position.z()
        val distance = computeDistance(deltaX, deltaY, deltaZ)
        if (distance <= configuration.finishDistance) return null

        val step = minOf(configuration.speed, distance)
        return computeNextPosition(position, deltaX, deltaY, deltaZ, distance, step)
    }

    private fun computeDistance(deltaX: Double, deltaY: Double, deltaZ: Double): Double =
        sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)

    private fun computeNextPosition(
        position: FinePosition,
        deltaX: Double, deltaY: Double, deltaZ: Double,
        distance: Double,
        step: Double,
    ): FinePosition = position.offset(deltaX / distance * step, deltaY / distance * step, deltaZ / distance * step)

    data class Configuration(
        val speed: Double = 0.12,
        val finishDistance: Double = 0.08,
    )
}
