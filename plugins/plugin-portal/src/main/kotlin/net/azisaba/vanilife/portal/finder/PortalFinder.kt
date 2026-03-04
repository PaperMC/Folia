package net.azisaba.vanilife.portal.finder

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import io.papermc.paper.math.BlockPosition
import io.papermc.paper.math.Position
import kotlinx.coroutines.withContext
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockState
import org.bukkit.plugin.Plugin

class PortalFinder(
    private val framePredicate: (BlockState) -> Boolean,
    private val allowedInnerWidth: IntRange,
    private val allowedInnerHeight: IntRange
) {
    suspend fun findPortal(plugin: Plugin, start: Location): DetectedPortal? {
        val world = start.world
        return withContext(plugin.regionDispatcher(start)) {
            if (!testFrameBlockAt(plugin, world, start.toBlock())) return@withContext null

            for (orientation in DetectedPortal.Orientation.entries) {
                for (dx in 0..(allowedInnerWidth.last() + 1)) {
                    for (dy in 0..(allowedInnerHeight.last() + 1)) {
                        val minX =
                            if (orientation == DetectedPortal.Orientation.XY) start.blockX() - dx else start.blockX
                        val minY = start.blockY() - dy
                        val minZ =
                            if (orientation == DetectedPortal.Orientation.XY) start.blockZ() else start.blockZ() - dx

                        val minBound = Position.block(minX, minY, minZ)

                        val candidate = estimateCandidate(plugin, world, minBound, orientation) ?: continue
                        if (!candidate.containsOnFrame(start.toBlock(), orientation)) continue
                        if (!validateCandidate(plugin, world, candidate, orientation)) continue

                        return@withContext candidate.toDetectedPortal(world, orientation)
                    }
                }
            }

            null
        }
    }

    private suspend fun validateCandidate(
        plugin: Plugin,
        world: World,
        candidate: Candidate,
        orientation: DetectedPortal.Orientation
    ): Boolean {
        for (dx in 0..(candidate.innerWidth + 1)) {
            if (!testFrameBlockAt(plugin, world, candidate.toBlockPosition(dx, 0, orientation))) return false
            if (!testFrameBlockAt(plugin, world, candidate.toBlockPosition(dx, candidate.innerHeight + 1, orientation)))
                return false
        }

        for (dy in 0..(candidate.innerHeight + 1)) {
            if (!testFrameBlockAt(plugin, world, candidate.toBlockPosition(0, dy, orientation))) return false
            if (!testFrameBlockAt(plugin, world, candidate.toBlockPosition(candidate.innerWidth + 1, dy, orientation)))
                return false
        }

        for (dx in 1..candidate.innerWidth) {
            for (dy in 1..candidate.innerHeight) {
                val blockState = getBlockStateAt(plugin, world, candidate.toBlockPosition(dx, dy, orientation))
                if (!blockState.type.isAir && blockState.type != Material.FIRE) return false
            }
        }

        return true
    }

    private suspend fun estimateCandidate(
        plugin: Plugin,
        world: World,
        minBound: BlockPosition,
        orientation: DetectedPortal.Orientation
    ): Candidate? {
        if (!testFrameBlockAt(plugin, world, minBound)) return null

        val innerWidth = measureWidth(plugin, world, minBound, orientation) - 2
        if (innerWidth !in allowedInnerWidth) return null

        val innerHeight = measureHeight(plugin, world, minBound, orientation) - 2
        if (innerHeight !in allowedInnerHeight) return null

        return Candidate(minBound, innerWidth, innerHeight)
    }

    private suspend fun measureWidth(
        plugin: Plugin,
        world: World,
        minBound: BlockPosition,
        orientation: DetectedPortal.Orientation
    ) = measureLength(plugin, world, minBound, orientation, Span.WIDTH, allowedInnerWidth.last() + 2)

    private suspend fun measureHeight(
        plugin: Plugin,
        world: World,
        minBound: BlockPosition,
        orientation: DetectedPortal.Orientation
    ) = measureLength(plugin, world, minBound, orientation, Span.HEIGHT, allowedInnerHeight.last() + 2)

    private suspend fun measureLength(
        plugin: Plugin,
        world: World,
        minBound: BlockPosition,
        orientation: DetectedPortal.Orientation,
        span: Span,
        limit: Int
    ): Int {
        var length = 0
        while (length < limit) {
            val x = when (span) {
                Span.WIDTH -> if (orientation == DetectedPortal.Orientation.XY) minBound.blockX() + length else minBound.blockX()
                Span.HEIGHT -> minBound.blockX()
            }
            val y = if (span == Span.WIDTH) minBound.blockY() else minBound.blockY() + length
            val z = when (span) {
                Span.WIDTH -> if (orientation == DetectedPortal.Orientation.XY) minBound.blockZ() else minBound.blockZ() + length
                Span.HEIGHT -> minBound.blockZ()
            }
            if (!testFrameBlockAt(plugin, world, Position.block(x, y, z))) break
            length++
        }
        return length
    }

    private suspend fun testFrameBlockAt(plugin: Plugin, world: World, pos: BlockPosition): Boolean {
        val state = getBlockStateAt(plugin, world, pos)
        return framePredicate(state)
    }

    private suspend fun getBlockStateAt(plugin: Plugin, world: World, pos: BlockPosition): BlockState {
        val chunkX = pos.blockX() shr 4
        val chunkZ = pos.blockZ() shr 4
        return if (plugin.server.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            world.getBlockAt(pos.blockX(), pos.blockY(), pos.blockZ()).state
        } else {
            val location = Location(world, pos.x(), pos.y(), pos.z())
            withContext(plugin.regionDispatcher(location)) { world.getBlockAt(location).state }
        }
    }

    private enum class Span { WIDTH, HEIGHT }

    private data class Candidate(val minBound: BlockPosition, val innerWidth: Int, val innerHeight: Int) {
        fun containsOnFrame(pos: BlockPosition, orientation: DetectedPortal.Orientation): Boolean =
            when (orientation) {
                DetectedPortal.Orientation.XY -> {
                    if (pos.blockZ() != minBound.blockZ()) return false
                    val xMin = minBound.blockX() + 1
                    val xMaxExcl = minBound.blockX() + innerWidth + 1
                    val yMin = minBound.blockY() + 1
                    val yMaxExcl = minBound.blockY() + innerHeight + 1
                    (pos.blockY() == minBound.blockY() && pos.blockX() in xMin until xMaxExcl) ||
                            (pos.blockY() == minBound.blockY() + innerHeight + 1 && pos.blockX() in xMin until xMaxExcl) ||
                            (pos.blockX() == minBound.blockX() && pos.blockY() in yMin until yMaxExcl) ||
                            (pos.blockX() == minBound.blockX() + innerWidth + 1 && pos.blockY() in yMin until yMaxExcl)
                }

                DetectedPortal.Orientation.ZY -> {
                    if (pos.blockX() != minBound.blockX()) return false
                    val zMin = minBound.blockZ() + 1
                    val zMaxExcl = minBound.blockZ() + innerWidth + 1
                    val yMin = minBound.blockY() + 1
                    val yMaxExcl = minBound.blockY() + innerHeight + 1
                    (pos.blockY() == minBound.blockY() && pos.blockZ() in zMin until zMaxExcl) ||
                            (pos.blockY() == minBound.blockY() + innerHeight + 1 && pos.blockZ() in zMin until zMaxExcl) ||
                            (pos.blockZ() == minBound.blockZ() && pos.blockY() in yMin until yMaxExcl) ||
                            (pos.blockZ() == minBound.blockZ() + innerWidth + 1 && pos.blockY() in yMin until yMaxExcl)
                }
            }

        fun toDetectedPortal(world: World, orientation: DetectedPortal.Orientation): DetectedPortal {
            val maxBound = when (orientation) {
                DetectedPortal.Orientation.XY ->
                    Position.block(
                        minBound.blockX() + innerWidth + 1,
                        minBound.blockY() + innerHeight + 1,
                        minBound.blockZ()
                    )

                DetectedPortal.Orientation.ZY ->
                    Position.block(
                        minBound.blockX(),
                        minBound.blockY() + innerHeight + 1,
                        minBound.blockZ() + innerWidth + 1
                    )
            }
            return DetectedPortal(world, innerWidth, innerHeight, minBound, maxBound, orientation)
        }

        fun toBlockPosition(dx: Int, dy: Int, orientation: DetectedPortal.Orientation): BlockPosition =
            Position.block(toBlockX(dx, orientation), toBlockY(dy), toBlockZ(dx, orientation))

        fun toBlockX(dx: Int, orientation: DetectedPortal.Orientation): Int =
            if (orientation == DetectedPortal.Orientation.XY) minBound.blockX() + dx else minBound.blockX()

        fun toBlockY(dy: Int): Int = minBound.blockY() + dy

        fun toBlockZ(dx: Int, orientation: DetectedPortal.Orientation): Int =
            if (orientation == DetectedPortal.Orientation.XY) minBound.blockZ() else minBound.blockZ() + dx
    }
}
