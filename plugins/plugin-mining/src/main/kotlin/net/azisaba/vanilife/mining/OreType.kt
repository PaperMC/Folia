package net.azisaba.vanilife.mining

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.keys.tags.BlockTypeTagKeys
import io.papermc.paper.registry.tag.TagKey
import net.azisaba.vanilife.item.ServerItem
import net.coreprotect.CoreProtectAPI
import org.bukkit.Material
import org.bukkit.block.Biome
import org.bukkit.block.BlockState
import org.bukkit.block.BlockType

@ConsistentCopyVisibility
data class OreType private constructor(val base: Material, val ingot: Material?, val frozen: TypedKey<ServerItem>, val blocks: TagKey<BlockType>) {
    val hasIngot: Boolean = ingot != null

    fun isBlock(blockState: BlockState): Boolean = RegistryAccess.registryAccess()
        .getRegistry(RegistryKey.BLOCK)
        .getTag(blocks)
        .contains(blockState.type.asBlockType()!!.key())

    companion object {
        private val SET: MutableSet<OreType> = mutableSetOf()
        private val BY_BASE: MutableMap<Material, OreType> = mutableMapOf()

        val COAL: OreType = register(OreType(Material.COAL, null, MiningItems.FROZEN_COAL, BlockTypeTagKeys.COAL_ORES))
        val RAW_COPPER: OreType = register(OreType(Material.RAW_COPPER, Material.COPPER_INGOT, MiningItems.FROZEN_RAW_COPPER, BlockTypeTagKeys.COPPER_ORES))
        val RAW_IRON: OreType = register(OreType(Material.RAW_IRON, Material.IRON_INGOT, MiningItems.FROZEN_RAW_IRON, BlockTypeTagKeys.IRON_ORES))
        val RAW_GOLD: OreType = register(OreType(Material.RAW_GOLD, Material.GOLD_INGOT, MiningItems.FROZEN_RAW_GOLD, BlockTypeTagKeys.GOLD_ORES))
        val REDSTONE: OreType = register(OreType(Material.REDSTONE, null, MiningItems.FROZEN_REDSTONE, BlockTypeTagKeys.REDSTONE_ORES))
        val LAPIS_LAZULI: OreType = register(OreType(Material.LAPIS_LAZULI, null, MiningItems.FROZEN_LAPIS_LAZULI, BlockTypeTagKeys.LAPIS_ORES))
        val EMERALD: OreType = register(OreType(Material.EMERALD, null, MiningItems.FROZEN_EMERALD, BlockTypeTagKeys.EMERALD_ORES))
        val DIAMOND: OreType = register(OreType(Material.DIAMOND, null, MiningItems.FROZEN_DIAMOND, BlockTypeTagKeys.DIAMOND_ORES))

        fun byBase(base: Material): OreType? = BY_BASE[base]

        fun byBlock(blockState: BlockState): OreType? =
            SET.firstOrNull { it.isBlock(blockState) }

        fun byBlockWithNaturalCheck(blockState: BlockState, coreProtectApi: CoreProtectAPI): OreType? {
            if (blockState.block.biome != Biome.GLACIAL_CAVE) return null

            val oreType = byBlock(blockState) ?: return null

            val lookup = coreProtectApi.blockLookup(blockState.block, 0) ?: return oreType
            for (row in lookup) {
                val result = coreProtectApi.parseResult(row)
                if (result.actionId == 1) {
                    return null
                }
            }

            return oreType
        }

        private fun register(value: OreType): OreType {
            SET.add(value)
            BY_BASE[value.base] = value
            return value
        }
    }
}
