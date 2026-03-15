package net.azisaba.vanilife.mining

import net.azisaba.packed.PackedKey
import net.azisaba.packed.model
import net.azisaba.packed.models.PackModel
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key

object MiningModels {
    val FROZEN_COAL: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/frozen_coal")
    val FROZEN_EMERALD: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/frozen_emerald")
    val FROZEN_LAPIS_LAZULI: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/frozen_lapis_lazuli")
    val FROZEN_RAW_COPPER: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/frozen_raw_copper")
    val FROZEN_RAW_GOLD: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/frozen_raw_gold")
    val FROZEN_RAW_IRON: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/frozen_raw_iron")
    val FROZEN_REDSTONE: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/frozen_redstone")

    fun frozenCoal(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/frozen_coal"))

    fun frozenEmerald(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/frozen_emerald"))

    fun frozenLapisLazuli(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/frozen_lapis_lazuli"))

    fun frozenRawCopper(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/frozen_raw_copper"))

    fun frozenRawGold(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/frozen_raw_gold"))

    fun frozenRawIron(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/frozen_raw_iron"))

    fun frozenRedstone(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/frozen_redstone"))
}
