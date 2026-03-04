package net.azisaba.vanilife.forestry.cutdown

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.util.Quaternion4f
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import me.tofaa.entitylib.container.EntityContainer
import me.tofaa.entitylib.meta.display.BlockDisplayMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.block.Block
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal class WrapperCutDownBlock(
    private val block: Block,
    private val pivot: Vector3d,
    private val rotationAxis: Vector3d,
    private val animationTime: Long,
) : WrapperEntity(EntityTypes.BLOCK_DISPLAY) {
    private val initialOffsetFromPivot: Vector3d = Vector3d(block.x - pivot.x, block.y - pivot.y, block.z - pivot.z)

    override fun spawn(location: Location, parent: EntityContainer): Boolean {
        if (!super.spawn(location, parent)) return false

        consumeEntityMeta(BlockDisplayMeta::class.java) { meta ->
            meta.blockState = SpigotConversionUtil.fromBukkitBlockData(block.blockData)
            meta.translation = Vector3f(0f, 0f, 0f)
            meta.leftRotation = Quaternion4f(0f, 0f, 0f, 1f)
            meta.interpolationDelay = 0
            meta.transformationInterpolationDuration = 2
        }

        return true
    }

    override fun tick(time: Long) {
        val progress = (time.toDouble() / animationTime).coerceIn(0.0, 1.0)

        val angle = (-(PI / 2.0) * progress).toFloat()

        val rotatedOffset = rotateVector(initialOffsetFromPivot, rotationAxis, angle)

        val blockPos = Vector3d(
            pivot.x + rotatedOffset.x,
            pivot.y + rotatedOffset.y,
            pivot.z + rotatedOffset.z,
        )

        val translation = Vector3f(
            (blockPos.x - block.x).toFloat(),
            (blockPos.y - block.y).toFloat(),
            (blockPos.z - block.z).toFloat(),
        )

        consumeEntityMeta(BlockDisplayMeta::class.java) { meta ->
            meta.translation = translation
            meta.leftRotation = axisAngleToQuaternion(rotationAxis, angle)
        }
        refresh()
    }

    private fun axisAngleToQuaternion(axis: Vector3d, angle: Float): Quaternion4f {
        val half = angle / 2f
        val sin = sin(half)
        val cos = cos(half)

        val nx = axis.x.toFloat()
        val ny = axis.y.toFloat()
        val nz = axis.z.toFloat()

        return Quaternion4f(
            nx * sin,
            ny * sin,
            nz * sin,
            cos,
        )
    }

    private fun rotateVector(vector: Vector3d, axis: Vector3d, angle: Float): Vector3d {
        val nx = axis.x
        val ny = axis.y
        val nz = axis.z

        val cos = cos(angle)
        val sin = sin(angle)

        val dot = vector.x * nx + vector.y * ny + vector.z * nz
        val crossX = ny * vector.z - nz * vector.y
        val crossY = nz * vector.x - nx * vector.z
        val crossZ = nx * vector.y - ny * vector.x

        return Vector3d(
            vector.x * cos + crossX * sin + nx * dot * (1 - cos),
            vector.y * cos + crossY * sin + ny * dot * (1 - cos),
            vector.z * cos + crossZ * sin + nz * dot * (1 - cos),
        )
    }
}
