package net.azisaba.vanilife.portal.finder

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import io.papermc.paper.math.BlockPosition
import io.papermc.paper.registry.keys.SoundEventKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.kyori.adventure.sound.Sound
import org.bukkit.*
import org.bukkit.block.data.Orientable
import org.bukkit.plugin.Plugin

data class DetectedPortal(
    val world: World,
    val innerWidth: Int,
    val innerHeight: Int,
    val minBound: BlockPosition,
    val maxBound: BlockPosition,
    val orientation: Orientation,
) {
    val innerXRange: IntRange = when (orientation) {
        Orientation.XY -> (minBound.blockX() + 1)..<maxBound.blockX()
        Orientation.ZY -> minBound.blockX()..maxBound.blockX()
    }

    val innerYRange: IntRange = (minBound.blockY() + 1)..<maxBound.blockY()

    val innerZRange: IntRange = when (orientation) {
        Orientation.XY -> minBound.blockZ()..maxBound.blockZ()
        Orientation.ZY -> (minBound.blockZ() + 1)..<maxBound.blockZ()
    }

    suspend fun fillPortalWithAnimation(plugin: Plugin) {
        val portalAxis = when (orientation) {
            Orientation.XY -> Axis.X
            Orientation.ZY -> Axis.Z
        }

        for (y in innerYRange.reversed()) {
            for (x in innerXRange) {
                for (z in innerZRange) {
                    val newBlockData = Material.NETHER_PORTAL.createBlockData {
                        (it as Orientable).axis = portalAxis
                    }

                    val current = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
                    if (!Bukkit.isOwnedByCurrentRegion(current)) {
                        withContext(plugin.regionDispatcher(current)) {
                            current.block.setBlockData(newBlockData, false)
                        }
                    } else {
                        current.block.setBlockData(newBlockData, false)
                    }
                }
            }
            delay(50L)
        }

        val centerX = (minBound.blockX() + maxBound.blockX() + 1) / 2.0
        val centerY = (minBound.blockY() + maxBound.blockY() + 1) / 2.0
        val centerZ = (minBound.blockZ() + maxBound.blockZ() + 1) / 2.0

        repeat(3) { time ->
            world.spawnParticle(
                Particle.EXPLOSION,
                centerX,
                centerY,
                centerZ,
                12,
                0.6,
                0.8,
                0.6,
            )
            world.spawnParticle(
                Particle.EXPLOSION,
                centerX,
                centerY,
                centerZ,
                20,
                innerWidth / 2.0,
                innerHeight / 2.0,
                innerWidth / 2.0,
                0.0,
            )
            world.playSound(
                Sound.sound(SoundEventKeys.BLOCK_RESPAWN_ANCHOR_CHARGE, Sound.Source.BLOCK, 0.9f, 0.9f + (time * 0.1f)),
                centerX, centerY, centerZ,
            )
            world.playSound(
                Sound.sound(SoundEventKeys.BLOCK_BEACON_AMBIENT, Sound.Source.BLOCK, 0.45f, 1.5f),
                centerX, centerY, centerZ,
            )
            delay(80L)
        }
    }

    enum class Orientation { XY, ZY }
}
