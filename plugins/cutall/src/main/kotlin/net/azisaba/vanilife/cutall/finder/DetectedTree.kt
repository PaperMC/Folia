package net.azisaba.vanilife.cutall.finder

import org.bukkit.block.Block

data class DetectedTree(
    val trunkBlocks: Set<Block>,
    val leavesBlocks: Set<Block>,
)
