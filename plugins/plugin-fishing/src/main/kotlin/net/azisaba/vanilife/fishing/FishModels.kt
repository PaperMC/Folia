package net.azisaba.vanilife.fishing

import net.azisaba.packed.PackedKey
import net.azisaba.packed.model
import net.azisaba.packed.models.PackModel
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key

object FishModels {
    val CLAM: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/clam")
    val CRUCIAN_CARP: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/crucian_carp")
    val EEL: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/eel")
    val FLATFISH: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/flatfish")
    val FLOUNDER: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/flounder")
    val HORSE_MACKEREL: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/horse_mackerel")
    val MACKEREL: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/mackerel")
    val MONKFISH: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/monkfish")
    val OCTOPUS: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/octopus")
    val SALMON: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/salmon")
    val SEA_BASS: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/sea_bass")
    val SEA_BREAM: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/sea_bream")
    val SEA_URCHIN: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/sea_urchin")
    val SPANISH_MACKEREL: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/spanish_mackerel")
    val SQUID: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/squid")
    val SWEETFISH: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/sweetfish")
    val TUNA: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/tuna")
    val YELLOWTAIL: PackedKey<PackModel> = PackedKey.model(Vanilife.NAMESPACE, "item/yellowtail")

    fun clam(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/clam"))

    fun crucianCarp(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/crucian_carp"))

    fun eel(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/eel"))

    fun flatfish(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/flatfish"))

    fun flounder(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/flounder"))

    fun horseMackerel(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/horse_mackerel"))

    fun mackerel(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/mackerel"))

    fun monkfish(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/monkfish"))

    fun octopus(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/octopus"))

    fun salmon(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/salmon"))

    fun seaBass(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/sea_bass"))

    fun seaBream(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/sea_bream"))

    fun seaUrchin(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/sea_urchin"))

    fun spanishMackerel(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/spanish_mackerel"))

    fun squid(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/squid"))

    fun sweetfish(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/sweetfish"))

    fun tuna(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/tuna"))

    fun yellowtail(): PackModel = PackModel.item(Key.key(Vanilife.NAMESPACE, "item/yellowtail"))
}
