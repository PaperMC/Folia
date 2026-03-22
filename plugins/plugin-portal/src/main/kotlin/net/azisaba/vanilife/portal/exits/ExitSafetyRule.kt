package net.azisaba.vanilife.portal.exits

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace

fun interface ExitSafetyRule {
    fun test(block: Block): Boolean

    companion object Default : ExitSafetyRule {
        override fun test(block: Block): Boolean = hasAirSpace(block) && hasSafeFloor(block)

        private fun hasAirSpace(block: Block): Boolean {
            val above = block.getRelative(BlockFace.UP)
            return block.isEmpty && above.isEmpty
        }

        private fun hasSafeFloor(block: Block): Boolean {
            val below = block.getRelative(BlockFace.DOWN)
            return below.isSolid && below.type != Material.MAGMA_BLOCK
        }
    }
}
