package net.azisaba.vanilife.forestry

import net.azisaba.packed.PackedKey
import net.azisaba.packed.itemModel
import net.azisaba.packed.items.PackItemModel
import net.azisaba.packed.items.properties.PackModelItemModelProperties
import net.azisaba.vanilife.Vanilife

object ForestryItemModels {
    val SMALL_TREE_STUMP: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "small_tree_stump")

    fun smallTreeStump(): PackItemModel = PackItemModel(PackModelItemModelProperties(ForestryModels.SMALL_TREE_STUMP))
}
