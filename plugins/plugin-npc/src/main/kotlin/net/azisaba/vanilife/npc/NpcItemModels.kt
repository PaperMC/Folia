package net.azisaba.vanilife.npc

import net.azisaba.packed.PackedKey
import net.azisaba.packed.itemModel
import net.azisaba.packed.items.PackItemModel
import net.azisaba.packed.items.properties.PackModelItemModelProperties
import net.azisaba.vanilife.Vanilife

object NpcItemModels {
    val EXPERIENCE: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "experience")
    val UNREADABLE_RECIPE: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "unreadable_recipe")

    fun experience(): PackItemModel = PackItemModel(PackModelItemModelProperties(NpcModels.EXPERIENCE))

    fun unreadableRecipe(): PackItemModel = PackItemModel(PackModelItemModelProperties(NpcModels.UNREADABLE_RECIPE))
}
