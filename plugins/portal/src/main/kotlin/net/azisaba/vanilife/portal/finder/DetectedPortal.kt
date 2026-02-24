package net.azisaba.vanilife.portal.finder

import io.papermc.paper.math.BlockPosition
import org.bukkit.World
import java.util.UUID

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
        DetectedPortal.Orientation.XY -> minBound.blockZ()..maxBound.blockZ()
        DetectedPortal.Orientation.ZY -> (minBound.blockZ() + 1)..<maxBound.blockZ()
    }

    enum class Orientation { XY, ZY }
}
