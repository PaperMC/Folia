package net.azisaba.vanilife.fishing.ai

import io.papermc.paper.math.FinePosition
import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DirectEscapeFightingBehavior(val configuration: Configuration = Configuration()) : FishBehavior.FightingBehavior {
    override fun tick(
        time: Long,
        position: FinePosition,
        fishHookPosition: FinePosition,
        playerPullStrength: Double,
    ): FinePosition {
        val deltaX = position.x() - fishHookPosition.x()
        val deltaY = position.y() - fishHookPosition.y()
        val deltaZ = position.z() - fishHookPosition.z()

        val distance = computeDistance(deltaX, deltaY, deltaZ)
        val direction = computeDirection(time, deltaX, deltaY, deltaZ, distance)
        val speed = computeSpeed(playerPullStrength)

        return position.offset(direction.x() * speed, direction.y() * speed, direction.z() * speed)
    }

    private fun computeDistance(deltaX: Double, deltaY: Double, deltaZ: Double): Double =
        sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)

    private fun computeSpeed(playerPullStrength: Double): Double =
        (configuration.speed - playerPullStrength * configuration.pullResistance).coerceAtLeast(configuration.minSpeed)

    private fun computeDirection(
        time: Long,
        deltaX: Double, deltaY: Double, deltaZ: Double,
        distance: Double,
    ): Vector3dc {
        if (distance <= configuration.minDistance) {
            val angle = time * configuration.fallbackPhaseStep
            return Vector3d(cos(angle), 0.0, sin(angle))
        }

        return Vector3d(deltaX / distance, deltaY / distance, deltaZ / distance)
    }

    data class Configuration(
        val speed: Double = 0.14,
        val minSpeed: Double = 0.04,
        val minDistance: Double = 0.02,
        val pullResistance: Double = 0.35,
        val fallbackPhaseStep: Double = 0.35,
    )
}
