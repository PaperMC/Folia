package net.azisaba.vanilife.farming

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.keys.BlockTypeKeys
import io.papermc.paper.registry.keys.ItemTypeKeys
import org.bukkit.Material
import org.bukkit.block.BlockType
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemType

@ConsistentCopyVisibility
data class Crop private constructor(
    val item: TypedKey<ItemType>,
    val seeds: TypedKey<ItemType>,
    val block: TypedKey<BlockType>
) {
    val isSelfSeeding: Boolean = item == seeds

    fun isBlock(material: Material): Boolean =
        RegistryKey.BLOCK.typedKey(material.asBlockType()!!.key()) == block

    fun isFullyGrownBlock(blockData: BlockData): Boolean =
        isBlock(blockData.material) && (blockData !is Ageable || blockData.age == blockData.maximumAge)

    fun unwrapItem(): ItemType = RegistryAccess.registryAccess()
        .getRegistry(RegistryKey.ITEM)
        .getOrThrow(item)

    fun unwrapSeeds(): ItemType = RegistryAccess.registryAccess()
        .getRegistry(RegistryKey.ITEM)
        .getOrThrow(seeds)

    fun unwrapBlock(): BlockType = RegistryAccess.registryAccess()
        .getRegistry(RegistryKey.BLOCK)
        .getOrThrow(block)

    companion object Values {
        private val SET: MutableSet<Crop> = mutableSetOf()

        val BEETROOT: Crop = register(Crop(ItemTypeKeys.BEETROOT, ItemTypeKeys.BEETROOT_SEEDS, BlockTypeKeys.BEETROOTS))
        val CARROT: Crop = register(Crop(ItemTypeKeys.CARROT, ItemTypeKeys.CARROT, BlockTypeKeys.CARROTS))
        val POTATO: Crop = register(Crop(ItemTypeKeys.POTATO, ItemTypeKeys.POTATO, BlockTypeKeys.POTATOES))
        val WHEAT: Crop = register(Crop(ItemTypeKeys.WHEAT, ItemTypeKeys.WHEAT_SEEDS, BlockTypeKeys.WHEAT))

        private fun register(value: Crop): Crop {
            SET.add(value)
            return value
        }
    }
}
