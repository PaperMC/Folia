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
data class FrozenOre private constructor(val item: TypedKey<ServerItem>, val base: Material, val ores: TagKey<BlockType>) {
    fun isOre(blockState: BlockState): Boolean = RegistryAccess.registryAccess()
        .getRegistry(RegistryKey.BLOCK)
        .getTag(ores)
        .contains(blockState.type.asBlockType()!!.key())

    companion object {
        private val SET: MutableSet<FrozenOre> = mutableSetOf()
        private val BY_BASE: MutableMap<Material, FrozenOre> = mutableMapOf()

        val COAL: FrozenOre = register(FrozenOre(MiningItems.FROZEN_COAL, Material.COAL, BlockTypeTagKeys.COAL_ORES))
        val RAW_COPPER: FrozenOre = register(FrozenOre(MiningItems.FROZEN_RAW_COPPER, Material.RAW_COPPER, BlockTypeTagKeys.COPPER_ORES))
        val RAW_IRON: FrozenOre = register(FrozenOre(MiningItems.FROZEN_RAW_IRON, Material.RAW_IRON, BlockTypeTagKeys.IRON_ORES))
        val RAW_GOLD: FrozenOre = register(FrozenOre(MiningItems.FROZEN_RAW_GOLD, Material.RAW_GOLD, BlockTypeTagKeys.GOLD_ORES))
        val REDSTONE: FrozenOre = register(FrozenOre(MiningItems.FROZEN_REDSTONE, Material.REDSTONE, BlockTypeTagKeys.REDSTONE_ORES))
        val LAPIS_LAZULI: FrozenOre = register(FrozenOre(MiningItems.FROZEN_LAPIS_LAZULI, Material.LAPIS_LAZULI, BlockTypeTagKeys.LAPIS_ORES))
        val EMERALD: FrozenOre = register(FrozenOre(MiningItems.FROZEN_EMERALD, Material.EMERALD, BlockTypeTagKeys.EMERALD_ORES))
        val DIAMOND: FrozenOre = register(FrozenOre(MiningItems.FROZEN_DIAMOND, Material.DIAMOND, BlockTypeTagKeys.DIAMOND_ORES))

        fun byBase(base: Material): FrozenOre? = BY_BASE[base]

        fun frozenOreOf(blockState: BlockState, coreProtectApiForNaturalCheck: CoreProtectAPI? = null): FrozenOre? {
            if (blockState.block.biome != Biome.GLACIAL_CAVE) return null

            val frozenOre = SET.firstOrNull { it.isOre(blockState) } ?: return null

            if (coreProtectApiForNaturalCheck != null) {
                val lookup = coreProtectApiForNaturalCheck.blockLookup(blockState.block, 0) ?: return frozenOre
                for (row in lookup) {
                    val result = coreProtectApiForNaturalCheck.parseResult(row)
                    if (result.actionId == 1) {
                        return null
                    }
                }
            }

            return frozenOre
        }

        private fun register(value: FrozenOre): FrozenOre {
            SET.add(value)
            BY_BASE[value.base] = value
            return value
        }
    }
}
