package net.azisaba.vanilife.fishing.ai

import io.papermc.paper.math.FinePosition
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class WavyApproachBehavior(val configuration: Configuration = Configuration()) : FishBehavior.ApproachBehavior {
    override fun tick(time: Long, position: FinePosition, fishHookPosition: FinePosition): FinePosition? {
        val deltaX = fishHookPosition.x() - position.x()
        val deltaY = fishHookPosition.y() - position.y()
        val deltaZ = fishHookPosition.z() - position.z()
        val distance = computeDistance(deltaX, deltaY, deltaZ)
        if (distance <= configuration.finishDistance) return null

        val directionX = deltaX / distance
        val directionY = deltaY / distance
        val directionZ = deltaZ / distance

        val lateralX = -directionZ
        val lateralZ = directionX

        val step = minOf(configuration.speed, distance)
        val swayScale = computeSwayScale(distance)
        val phase = time * configuration.phaseStep
        val sway = sin(phase) * swayScale

        return computeNextPosition(position, directionX, directionY, directionZ, lateralX, lateralZ, step, sway, phase)
    }

    private fun computeDistance(deltaX: Double, deltaY: Double, deltaZ: Double): Double =
        sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)

    private fun computeSwayScale(distance: Double): Double =
        minOf(configuration.maxSway, distance * configuration.swayDistanceFactor)

    private fun computeNextPosition(
        position: FinePosition,
        directionX: Double, directionY: Double, directionZ: Double,
        lateralX: Double, lateralZ: Double,
        step: Double,
        sway: Double,
        phase: Double,
    ): FinePosition = position.offset(
        directionX * step + lateralX * sway,
        directionY * step + cos(phase) * configuration.verticalWave,
        directionZ * step + lateralZ * sway,
    )

    data class Configuration(
        val speed: Double = 0.12,
        val finishDistance: Double = 0.08,
        val maxSway: Double = 0.06,
        val verticalWave: Double = 0.004,
        val swayDistanceFactor: Double = 0.22,
        val phaseStep: Double = 0.55,
    )
}
