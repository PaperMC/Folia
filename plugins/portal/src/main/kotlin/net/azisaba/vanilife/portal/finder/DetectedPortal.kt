package net.azisaba.vanilife.portal.finder

import io.papermc.paper.math.BlockPosition
import java.util.UUID

data class DetectedPortal(
    val minBound: BlockPosition,
    val maxBound: BlockPosition,
    val innerWidth: Int,
    val innerHeight: Int,
    val orientation: Orientation,
) {
    enum class Orientation { XY, ZY }
}
