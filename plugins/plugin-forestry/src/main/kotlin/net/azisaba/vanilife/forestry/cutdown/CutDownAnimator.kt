package net.azisaba.vanilife.forestry.cutdown

import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.util.Vector3d
import io.papermc.paper.registry.keys.SoundEventKeys
import kotlinx.coroutines.delay
import me.tofaa.entitylib.container.EntityContainer
import net.azisaba.vanilife.forestry.finder.DetectedTree
import net.kyori.adventure.sound.Sound
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import java.lang.Math.toRadians
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class CutDownAnimator(
    private val sound: Sound = Sound.sound(SoundEventKeys.BLOCK_CHEST_OPEN, Sound.Source.BLOCK, 0.5f, 0.1f),
    private val animationTime: Long = 20L,
) {
    suspend fun animate(context: CutDownContext) {
        val pivot = pivotVec(context.detectedTree) ?: return

        playSound(context)

        val entityContainer = EntityContainer.basic()
        val animationViewers = context.chunk.playersSeeingChunk.map(Player::getUniqueId)
        val rotationAxis = computeRotationAxis(context.player.yaw)
        for (block in context.detectedTree) {
            val wrapperBlock = WrapperCutDownBlock(block, pivot, rotationAxis, animationTime)

            val spawnLocation = Location(block.x.toDouble(), block.y.toDouble(), block.z.toDouble(), 0f, 0f)
            wrapperBlock.spawn(spawnLocation, entityContainer)
            animationViewers.forEach(wrapperBlock::addViewer)

            block.type = Material.AIR
        }

        delay(50L)
        for (time in 1..animationTime) {
            entityContainer.entities.forEach { it.tick(time) }
            delay(50L)
        }

        entityContainer.clearEntities(true)
    }

    private fun pivotVec(detectedTree: DetectedTree): Vector3d? {
        val block = detectedTree.trunkBlocks.minByOrNull(Block::getY) ?: return null
        return Vector3d(block.x.toDouble(), block.y.toDouble(), block.z.toDouble())
    }

    private fun computeRotationAxis(playerYaw: Float): Vector3d {
        val yawRad = toRadians(playerYaw.toDouble())
        val fallDir = Vector3d(sin(yawRad), 0.0, -cos(yawRad))
        return normalize(Vector3d(fallDir.z, 0.0, -fallDir.x))
    }

    private fun normalize(axis: Vector3d): Vector3d {
        val len = sqrt((axis.x * axis.x + axis.y * axis.y + axis.z * axis.z).toFloat()).toDouble()
        if (len == 0.0) return Vector3d(0.0, 0.0, 1.0)
        return Vector3d(axis.x / len, axis.y / len, axis.z / len)
    }

    private fun playSound(context: CutDownContext) {
        context.world.playSound(
            sound,
            context.sourceBlock.x.toDouble(),
            context.sourceBlock.y.toDouble(),
            context.sourceBlock.z.toDouble(),
        )
    }
}
