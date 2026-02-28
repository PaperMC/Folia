package net.azisaba.vanilife.islands

import com.github.retrooper.packetevents.util.Quaternion4f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class CoastSide(val axisX: Boolean, val axisZ: Boolean, val yaw: Float, val coastNormalSign: Double, val rotation: Quaternion4f) {
    NORTH(false, true, -90f, -1.0, axisAngle(-1f, 1f, 1f, 120f)),
    SOUTH(false, true, -90f, 1.0, axisAngle(-1f, -1f, -1f, 120f)),
    EAST(true, false, 90f, 1.0, axisAngle(0f, 1f, 1f, 180f)),
    WEST(true, false, 90f, -1.0, axisAngle(1f, 0f, 0f, -90f));
}

fun IslandPos.boundaryBlock(coastSide: CoastSide): Int = when {
    coastSide.axisX && coastSide.coastNormalSign < 0 -> minBlockX()
    coastSide.axisX && coastSide.coastNormalSign > 0 -> maxBlockX()
    coastSide.axisZ && coastSide.coastNormalSign < 0 -> minBlockZ()
    coastSide.axisZ && coastSide.coastNormalSign > 0 -> maxBlockZ()
    else -> error("Invalid CoastSide: $coastSide")
}

private fun axisAngle(ax: Float, ay: Float, az: Float, degrees: Float): Quaternion4f {
    val len = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
    val nx = ax / len
    val ny = ay / len
    val nz = az / len
    val half = Math.toRadians((degrees / 2.0f).toDouble())
    val s = sin(half).toFloat()
    val c = cos(half).toFloat()
    return Quaternion4f(nx * s, ny * s, nz * s, c)
}
