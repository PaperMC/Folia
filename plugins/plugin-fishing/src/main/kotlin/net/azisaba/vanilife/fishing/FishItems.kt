package net.azisaba.vanilife.fishing

import io.papermc.paper.datacomponent.item.FoodProperties
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.event.RegistryComposeEvent
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.item.ServerItemType
import net.azisaba.vanilife.registry.data.ServerItemTypeRegistryEntry
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component

object FishItems {
    val CLAM: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "clam"))
    val CRUCIAN_CARP: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "crucian_carp"))
    val EEL: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "eel"))
    val FLATFISH: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "flatfish"))
    val FLOUNDER: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "flounder"))
    val HORSE_MACKEREL: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "horse_mackerel"))
    val MACKEREL: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "mackerel"))
    val MONKFISH: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "monkfish"))
    val OCTOPUS: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "octopus"))
    val SALMON: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "salmon"))
    val SEA_BASS: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sea_bass"))
    val SEA_BREAM: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sea_bream"))
    val SEA_URCHIN: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sea_urchin"))
    val SPANISH_MACKEREL: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "spanish_mackerel"))
    val SQUID: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "squid"))
    val SWEETFISH: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sweetfish"))
    val TUNA: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "tuna"))
    val YELLOWTAIL: TypedKey<ServerItemType> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "yellowtail"))

    fun bootstrap(event: RegistryComposeEvent<ServerItemType, ServerItemTypeRegistryEntry.Builder>) {
        event.registry().register(CLAM, ::clam)
        event.registry().register(CRUCIAN_CARP, ::crucianCarp)
        event.registry().register(EEL, ::eel)
        event.registry().register(FLATFISH, ::flatfish)
        event.registry().register(FLOUNDER, ::flounder)
        event.registry().register(HORSE_MACKEREL, ::horseMackerel)
        event.registry().register(MACKEREL, ::mackerel)
        event.registry().register(MONKFISH, ::monkfish)
        event.registry().register(OCTOPUS, ::octopus)
        event.registry().register(SALMON, ::salmon)
        event.registry().register(SEA_BASS, ::seaBass)
        event.registry().register(SEA_BREAM, ::seaBream)
        event.registry().register(SEA_URCHIN, ::seaUrchin)
        event.registry().register(SPANISH_MACKEREL, ::spanishMackerel)
        event.registry().register(SQUID, ::squid)
        event.registry().register(SWEETFISH, ::sweetfish)
        event.registry().register(TUNA, ::tuna)
        event.registry().register(YELLOWTAIL, ::yellowtail)
    }

    private fun clam(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_CLAM))
            .itemModel(FishItemModels.CLAM)
            .food(FoodProperties.food().nutrition(2).saturation(0.2f).build())
            .peakSeason(*FishType.CLAM.peakSeason.toTypedArray())
    }

    private fun crucianCarp(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_CRUCIAN_CARP))
            .itemModel(FishItemModels.CRUCIAN_CARP)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.CRUCIAN_CARP.peakSeason.toTypedArray())
    }

    private fun eel(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_EEL))
            .itemModel(FishItemModels.EEL)
            .food(FoodProperties.food().nutrition(3).saturation(0.3f).build())
            .peakSeason(*FishType.EEL.peakSeason.toTypedArray())
    }

    private fun flatfish(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_FLATFISH))
            .itemModel(FishItemModels.FLATFISH)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.FLATFISH.peakSeason.toTypedArray())
    }

    private fun flounder(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_FLOUNDER))
            .itemModel(FishItemModels.FLOUNDER)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.FLOUNDER.peakSeason.toTypedArray())
    }

    private fun horseMackerel(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_HORSE_MACKEREL))
            .itemModel(FishItemModels.HORSE_MACKEREL)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.HORSE_MACKEREL.peakSeason.toTypedArray())
    }

    private fun mackerel(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_MACKEREL))
            .itemModel(FishItemModels.MACKEREL)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.MACKEREL.peakSeason.toTypedArray())
    }

    private fun monkfish(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_MONKFISH))
            .itemModel(FishItemModels.MONKFISH)
            .food(FoodProperties.food().nutrition(3).saturation(0.2f).build())
            .peakSeason(*FishType.MONKFISH.peakSeason.toTypedArray())
    }

    private fun octopus(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_OCTOPUS))
            .itemModel(FishItemModels.OCTOPUS)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.OCTOPUS.peakSeason.toTypedArray())
    }

    private fun salmon(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_SALMON))
            .itemModel(FishItemModels.SALMON)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SALMON.peakSeason.toTypedArray())
    }

    private fun seaBass(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_SEA_BASS))
            .itemModel(FishItemModels.SEA_BASS)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SEA_BASS.peakSeason.toTypedArray())
    }

    private fun seaBream(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_SEA_BREAM))
            .itemModel(FishItemModels.SEA_BREAM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SEA_BREAM.peakSeason.toTypedArray())
    }

    private fun seaUrchin(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_SEA_URCHIN))
            .itemModel(FishItemModels.SEA_URCHIN)
            .food(FoodProperties.food().nutrition(2).saturation(0.2f).build())
            .peakSeason(*FishType.SEA_URCHIN.peakSeason.toTypedArray())
    }

    private fun spanishMackerel(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_SPANISH_MACKEREL))
            .itemModel(FishItemModels.SPANISH_MACKEREL)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SPANISH_MACKEREL.peakSeason.toTypedArray())
    }

    private fun squid(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_SQUID))
            .itemModel(FishItemModels.SQUID)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SQUID.peakSeason.toTypedArray())
    }

    private fun sweetfish(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_SWEETFISH))
            .itemModel(FishItemModels.SWEETFISH)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SWEETFISH.peakSeason.toTypedArray())
    }

    private fun tuna(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_TUNA))
            .itemModel(FishItemModels.TUNA)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.TUNA.peakSeason.toTypedArray())
    }

    private fun yellowtail(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(FishTranslations.ITEM_VANILIFE_YELLOWTAIL))
            .itemModel(FishItemModels.YELLOWTAIL)
            .food(FoodProperties.food().nutrition(3).saturation(0.2f).build())
            .peakSeason(*FishType.YELLOWTAIL.peakSeason.toTypedArray())
    }
}
