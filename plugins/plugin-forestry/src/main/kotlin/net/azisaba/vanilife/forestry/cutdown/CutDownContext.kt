package net.azisaba.vanilife.forestry.cutdown

import net.azisaba.vanilife.forestry.finder.DetectedTree
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player

internal data class CutDownContext(
    val player: Player,
    val sourceBlock: Block,
    val detectedTree: DetectedTree,
    val world: World = sourceBlock.world,
    val chunk: Chunk = sourceBlock.chunk,
)
