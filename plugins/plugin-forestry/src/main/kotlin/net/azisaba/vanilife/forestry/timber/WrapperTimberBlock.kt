package net.azisaba.vanilife.forestry.timber

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.particle.Particle
import com.github.retrooper.packetevents.protocol.particle.data.ParticleBlockStateData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleColorData
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.util.Quaternion4f
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import me.tofaa.entitylib.container.EntityContainer
import me.tofaa.entitylib.meta.display.BlockDisplayMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.Material
import org.bukkit.block.BlockState
import org.bukkit.block.data.type.Leaves
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal class WrapperTimberBlock(
    private val blockState: BlockState,
    private val pivot: Vector3d,
    private val rotationAxis: Vector3d,
    private val animationTime: Long,
) : WrapperEntity(EntityTypes.BLOCK_DISPLAY) {
    private val initialOffsetFromPivot: Vector3d =
        Vector3d(blockState.x - pivot.x, blockState.y - pivot.y, blockState.z - pivot.z)

    override fun spawn(location: Location, parent: EntityContainer): Boolean {
        if (!super.spawn(location, parent)) return false

        consumeEntityMeta(BlockDisplayMeta::class.java) { meta ->
            meta.blockState = SpigotConversionUtil.fromBukkitBlockData(blockState.blockData)
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
        val blockPos = computeBlockPosition(angle)

        val translation = Vector3f(
            (blockPos.x - blockState.x).toFloat(),
            (blockPos.y - blockState.y).toFloat(),
            (blockPos.z - blockState.z).toFloat(),
        )

        consumeEntityMeta(BlockDisplayMeta::class.java) { meta ->
            meta.translation = translation
            meta.leftRotation = axisAngleToQuaternion(rotationAxis, angle)
        }
        refresh()

        if (time == animationTime) {
            endTick(blockPos)
        }
    }

    private fun endTick(blockPos: Vector3d) {
        val particleX = blockPos.x + 0.5
        val particleY = blockPos.y + 0.25
        val particleZ = blockPos.z + 0.5

        val cloudPacket = WrapperPlayServerParticle(
            Particle(ParticleTypes.CLOUD),
            false,
            Vector3d(particleX, particleY, particleZ),
            Vector3f(0.18f, 0.08f, 0.18f),
            0.02f,
            6,
            false,
        )

        val blockPacket = WrapperPlayServerParticle(
            Particle(
                ParticleTypes.BLOCK,
                ParticleBlockStateData(SpigotConversionUtil.fromBukkitBlockData(blockState.blockData)),
            ),
            false,
            Vector3d(particleX, particleY, particleZ),
            Vector3f(0.22f, 0.12f, 0.22f),
            0.0f,
            8,
            false,
        )

        sendPacketsToViewers(cloudPacket)
        sendPacketsToViewers(blockPacket)
        createLeavesPacket(particleX, particleY, particleZ)?.let { leavesPacket ->
            sendPacketsToViewers(leavesPacket)
        }
    }

    private fun computeBlockPosition(angle: Float): Vector3d {
        val rotatedOffset = rotateVector(initialOffsetFromPivot, rotationAxis, angle)
        return Vector3d(pivot.x + rotatedOffset.x, pivot.y + rotatedOffset.y, pivot.z + rotatedOffset.z)
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

    private fun createLeavesPacket(particleX: Double, particleY: Double, particleZ: Double): WrapperPlayServerParticle? {
        if (blockState.blockData !is Leaves) return null

        val particle = when (blockState.type) {
            Material.CHERRY_LEAVES -> Particle(ParticleTypes.CHERRY_LEAVES)
            Material.PALE_OAK_LEAVES -> Particle(ParticleTypes.PALE_OAK_LEAVES)
            else -> Particle(ParticleTypes.TINTED_LEAVES, ParticleColorData(0x6BAF45))
        }

        return WrapperPlayServerParticle(
            particle,
            false,
            Vector3d(particleX, particleY + 0.15, particleZ),
            Vector3f(0.28f, 0.16f, 0.28f),
            0.01f,
            4,
            false,
        )
    }
}
