package net.azisaba.vanilife.fishing

import net.azisaba.packed.PackedKey
import net.azisaba.packed.itemModel
import net.azisaba.packed.items.PackItemModel
import net.azisaba.packed.items.properties.PackModelItemModelProperties
import net.azisaba.vanilife.Vanilife

object FishingItemModels {
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

    fun clamItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.CLAM))

    fun crucianCarpItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.CRUCIAN_CARP))

    fun eelItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.EEL))

    fun flatfishItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.FLATFISH))

    fun flounderItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.FLOUNDER))

    fun horseMackerelItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.HORSE_MACKEREL))

    fun mackerelItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.MACKEREL))

    fun monkfishItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.MONKFISH))

    fun octopusItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.OCTOPUS))

    fun salmonItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.SALMON))

    fun seaBassItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.SEA_BASS))

    fun seaBreamItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.SEA_BREAM))

    fun seaUrchinItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.SEA_URCHIN))

    fun spanishMackerelItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.SPANISH_MACKEREL))

    fun squidItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.SQUID))

    fun sweetfishItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.SWEETFISH))

    fun tunaItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.TUNA))

    fun yellowtailItem(): PackItemModel = PackItemModel(PackModelItemModelProperties(FishingModels.YELLOWTAIL))
}
