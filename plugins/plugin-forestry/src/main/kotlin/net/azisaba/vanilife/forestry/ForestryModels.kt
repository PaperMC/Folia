package net.azisaba.vanilife.forestry

import net.azisaba.packed.PackedKey
import net.azisaba.packed.model
import net.azisaba.packed.models.PackModel
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key

object ForestryModels {
    val SMALL_TREE_STUMP: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/small_tree_stump")

    fun smallTreeStump(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/small_tree_stump"))
}
