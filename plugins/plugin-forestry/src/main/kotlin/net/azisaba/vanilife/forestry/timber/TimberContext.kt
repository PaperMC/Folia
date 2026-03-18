package net.azisaba.vanilife.forestry.timber

import net.azisaba.vanilife.forestry.finder.DetectedTree
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.block.BlockState
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

internal data class TimberContext(
    val player: Player,
    val axe: ItemStack,
    val source: BlockState,
    val detectedTree: DetectedTree,
    val world: World = source.world,
    val chunk: Chunk = source.chunk,
    val x: Int = source.x,
    val y: Int = source.y,
    val z: Int = source.z,
)
