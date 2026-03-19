package net.azisaba.vanilife.farming

import net.azisaba.packed.PackedKey
import net.azisaba.packed.model
import net.azisaba.packed.models.PackModel
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key

object FarmingModels {
    val FERTILIZER: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/fertilizer")

    fun wateringCan(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/fertilizer"))
}
