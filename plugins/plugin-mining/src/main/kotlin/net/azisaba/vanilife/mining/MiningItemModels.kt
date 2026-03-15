package net.azisaba.vanilife.mining

import net.azisaba.packed.PackedKey
import net.azisaba.packed.itemModel
import net.azisaba.packed.items.PackItemModel
import net.azisaba.packed.items.properties.PackModelItemModelProperties
import net.azisaba.vanilife.Vanilife

object MiningItemModels {
    val FROZEN_COAL: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "frozen_from_coal")
    val FROZEN_DIAMOND: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "frozen_diamond")
    val FROZEN_EMERALD: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "frozen_from_emerald")
    val FROZEN_LAPIS_LAZULI: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "frozen_lapis_lazuli")
    val FROZEN_RAW_COPPER: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "frozen_raw_copper")
    val FROZEN_RAW_GOLD: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "frozen_raw_gold")
    val FROZEN_RAW_IRON: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "frozen_raw_iron")
    val FROZEN_REDSTONE: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "frozen_redstone")

    fun frozenCoal(): PackItemModel = PackItemModel(PackModelItemModelProperties(MiningModels.FROZEN_COAL))

    fun frozenDiamond(): PackItemModel = PackItemModel(PackModelItemModelProperties(MiningModels.FROZEN_DIAMOND))

    fun frozenEmerald(): PackItemModel = PackItemModel(PackModelItemModelProperties(MiningModels.FROZEN_EMERALD))

    fun frozenLapisLazuli(): PackItemModel = PackItemModel(PackModelItemModelProperties(MiningModels.FROZEN_LAPIS_LAZULI))

    fun frozenRawCopper(): PackItemModel = PackItemModel(PackModelItemModelProperties(MiningModels.FROZEN_RAW_COPPER))

    fun frozenRawGold(): PackItemModel = PackItemModel(PackModelItemModelProperties(MiningModels.FROZEN_RAW_GOLD))

    fun frozenRawIron(): PackItemModel = PackItemModel(PackModelItemModelProperties(MiningModels.FROZEN_RAW_IRON))

    fun frozenRedstone(): PackItemModel = PackItemModel(PackModelItemModelProperties(MiningModels.FROZEN_REDSTONE))
}
