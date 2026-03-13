package net.azisaba.vanilife.npc

import net.azisaba.packed.PackedKey
import net.azisaba.packed.model
import net.azisaba.packed.models.PackModel
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key

object NpcModels {
    val UNREADABLE_RECIPE: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/unreadable_recipe")

    fun unreadableRecipe(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/unreadable_recipe"))
}
