package net.azisaba.vanilife.portal

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kotlinx.coroutines.delay
import net.azisaba.vanilife.portal.finder.DetectedPortal
import net.azisaba.vanilife.portal.finder.PortalFinder
import org.bukkit.Axis
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.data.Orientable
import org.bukkit.plugin.Plugin

object ResourcePortals {
    val FRAME_BLOCK: Material = Material.PRISMARINE

    val finder: PortalFinder = PortalFinder(
        { it.type == FRAME_BLOCK },
        2..21,
        3..21
    )
    fun createWithAnimation(plugin: Plugin, detected: DetectedPortal) {
        val location = Location(detected.world, detected.minBound.x(), detected.minBound.y(), detected.maxBound.z())
        val portalAxis = when (detected.orientation) {
            DetectedPortal.Orientation.XY -> Axis.X
            DetectedPortal.Orientation.ZY -> Axis.Z
        }
        plugin.launch(plugin.regionDispatcher(location)) {
            for (y in detected.innerYRange.reversed()) {
                for (x in detected.innerXRange) {
                    for (z in detected.innerZRange) {
                        val block = detected.world.getBlockAt(x, y, z)
                        val blockData = Material.NETHER_PORTAL.createBlockData() as Orientable
                        blockData.axis = portalAxis
                        block.setBlockData(blockData, false)
                    }
                }
                delay(50)
            }

            val centerX = (detected.minBound.blockX() + detected.maxBound.blockX() + 1) / 2.0
            val centerY = (detected.minBound.blockY() + detected.maxBound.blockY() + 1) / 2.0
            val centerZ = (detected.minBound.blockZ() + detected.maxBound.blockZ() + 1) / 2.0

            repeat(3) {
                detected.world.spawnParticle(
                    Particle.EXPLOSION,
                    centerX,
                    centerY,
                    centerZ,
                    12,
                    0.6,
                    0.8,
                    0.6,
                    0.0
                )
                detected.world.spawnParticle(
                    Particle.EXPLOSION,
                    centerX,
                    centerY,
                    centerZ,
                    20,
                    detected.innerWidth / 2.0,
                    detected.innerHeight / 2.0,
                    detected.innerWidth / 2.0,
                    0.0
                )
                detected.world.playSound(
                    Location(detected.world, centerX, centerY, centerZ),
                    Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                    SoundCategory.BLOCKS,
                    0.9f,
                    0.9f + (it * 0.1f)
                )
                detected.world.playSound(
                    Location(detected.world, centerX, centerY, centerZ),
                    Sound.BLOCK_BEACON_AMBIENT,
                    SoundCategory.BLOCKS,
                    0.45f,
                    1.5f
                )
                delay(80)
            }
        }
    }
}
