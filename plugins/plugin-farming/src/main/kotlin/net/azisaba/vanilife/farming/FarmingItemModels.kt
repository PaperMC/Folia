package net.azisaba.vanilife.farming

import net.azisaba.packed.PackedKey
import net.azisaba.packed.itemModel
import net.azisaba.packed.items.PackItemModel
import net.azisaba.packed.items.properties.PackModelItemModelProperties
import net.azisaba.vanilife.Vanilife

object FarmingItemModels {
    val FERTILIZER: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "fertilizer")

    fun wateringCan(): PackItemModel = PackItemModel(PackModelItemModelProperties(FarmingModels.FERTILIZER))
}
