package net.azisaba.vanilife.forestry.finder

import org.bukkit.block.Block
import org.bukkit.plugin.Plugin

data class TreeFinderRouter(val finders: List<TreeFinder>) {
    suspend fun find(start: Block): DetectedTree? =
        findApplicableFinders(start).firstNotNullOfOrNull { finder -> finder.find(start) }

    fun findApplicableFinders(block: Block) = finders.filter { it.isTrunkBlock(block, block) }

    companion object {
        fun build(plugin: Plugin): TreeFinderRouter = TreeFinderRouter(listOf(
            TreeFinder.oak(plugin),
            TreeFinder.fancyOak(plugin),
            TreeFinder.spruce(plugin),
            TreeFinder.birch(plugin),
            TreeFinder.jungle(plugin),
            TreeFinder.acacia(plugin),
            TreeFinder.darkOak(plugin),
            TreeFinder.paleOak(plugin),
        ))
    }
}
