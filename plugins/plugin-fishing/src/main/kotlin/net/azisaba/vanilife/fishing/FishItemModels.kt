package net.azisaba.vanilife.fishing

import net.azisaba.packed.PackedKey
import net.azisaba.packed.itemModel
import net.azisaba.packed.items.PackItemModel
import net.azisaba.packed.items.properties.PackModelItemModelProperties
import net.azisaba.vanilife.Vanilife

object FishItemModels {
    val CLAM: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "clam")
    val CRUCIAN_CARP: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "crucian_carp")
    val EEL: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "eel")
    val FLATFISH: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "flatfish")
    val FLOUNDER: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "flounder")
    val HORSE_MACKEREL: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "horse_mackerel")
    val MACKEREL: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "mackerel")
    val MONKFISH: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "monkfish")
    val OCTOPUS: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "octopus")
    val SALMON: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "salmon")
    val SEA_BASS: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "sea_bass")
    val SEA_BREAM: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "sea_bream")
    val SEA_URCHIN: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "sea_urchin")
    val SPANISH_MACKEREL: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "spanish_mackerel")
    val SQUID: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "squid")
    val SWEETFISH: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "sweetfish")
    val TUNA: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "tuna")
    val YELLOWTAIL: PackedKey<PackItemModel> = PackedKey.itemModel(Vanilife.NAMESPACE, "yellowtail")

    fun clamItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.CLAM))

    fun crucianCarpItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.CRUCIAN_CARP))

    fun eelItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.EEL))

    fun flatfishItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.FLATFISH))

    fun flounderItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.FLOUNDER))

    fun horseMackerelItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.HORSE_MACKEREL))

    fun mackerelItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.MACKEREL))

    fun monkfishItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.MONKFISH))

    fun octopusItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.OCTOPUS))

    fun salmonItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.SALMON))

    fun seaBassItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.SEA_BASS))

    fun seaBreamItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.SEA_BREAM))

    fun seaUrchinItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.SEA_URCHIN))

    fun spanishMackerelItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.SPANISH_MACKEREL))

    fun squidItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.SQUID))

    fun sweetfishItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.SWEETFISH))

    fun tunaItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.TUNA))

    fun yellowtailItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishModels.YELLOWTAIL))
}
