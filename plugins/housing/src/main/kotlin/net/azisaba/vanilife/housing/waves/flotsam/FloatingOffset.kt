package net.azisaba.vanilife.housing.waves.flotsam

import com.github.retrooper.packetevents.protocol.world.Location
import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FloatingOffset(random: Random) {
    private val amplitude: Vector3dc = Vector3d(
        random.nextDouble(0.05, 0.18),
        random.nextDouble(0.18, 0.42),
        random.nextDouble(0.05, 0.18),
    )
    private val frequency: Vector3dc = Vector3d(
        random.nextDouble(1.0, 2.2),
        random.nextDouble(1.6, 3.0),
        random.nextDouble(1.0, 2.2),
    )
    private val phase: Vector3dc = Vector3d(
        random.nextDouble(0.0, PI * 2.0),
        random.nextDouble(0.0, PI * 2.0),
        random.nextDouble(0.0, PI * 2.0)
    )

    fun compute(base: Location, tick: Long, intensity: Double): Location {
        val t = tick.toDouble() / TICKS_PER_SECOND
        val dx = sin(t * frequency.x() + phase.x()) * amplitude.x() * intensity
        val dy = sin(t * frequency.y() + phase.y()) * amplitude.y() * intensity
        val dz = cos(t * frequency.z() + phase.z()) * amplitude.z() * intensity
        return Location(
            base.x + dx,
            base.y + dy,
            base.z + dz,
            base.yaw,
            base.pitch,
        )
    }

    companion object {
        private const val TICKS_PER_SECOND = 20.0
    }
}
