package net.azisaba.vanilife.fishing

import io.papermc.paper.datacomponent.item.FoodProperties
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.event.RegistryComposeEvent
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.item.ServerItem
import net.azisaba.vanilife.registry.data.ServerItemCategory
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry
import net.kyori.adventure.key.Key

object FishingItems {
    val CLAM: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "clam"))
    val CRUCIAN_CARP: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "crucian_carp"))
    val EEL: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "eel"))
    val FLATFISH: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "flatfish"))
    val FLOUNDER: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "flounder"))
    val HORSE_MACKEREL: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "horse_mackerel"))
    val MACKEREL: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "mackerel"))
    val MONKFISH: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "monkfish"))
    val OCTOPUS: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "octopus"))
    val SALMON: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "salmon"))
    val SEA_BASS: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sea_bass"))
    val SEA_BREAM: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sea_bream"))
    val SEA_URCHIN: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sea_urchin"))
    val SPANISH_MACKEREL: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "spanish_mackerel"))
    val SQUID: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "squid"))
    val SWEETFISH: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "sweetfish"))
    val TUNA: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "tuna"))
    val YELLOWTAIL: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "yellowtail"))

    fun bootstrap(event: RegistryComposeEvent<ServerItem, ServerItemRegistryEntry.Builder>) {
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

    private fun clam(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_CLAM)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.CLAM)
            .food(FoodProperties.food().nutrition(2).saturation(0.2f).build())
            .peakSeason(*FishType.CLAM.peakSeason.toTypedArray())
    }

    private fun crucianCarp(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_CRUCIAN_CARP)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.CRUCIAN_CARP)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.CRUCIAN_CARP.peakSeason.toTypedArray())
    }

    private fun eel(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_EEL)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.EEL)
            .food(FoodProperties.food().nutrition(3).saturation(0.3f).build())
            .peakSeason(*FishType.EEL.peakSeason.toTypedArray())
    }

    private fun flatfish(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_FLATFISH)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.FLATFISH)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.FLATFISH.peakSeason.toTypedArray())
    }

    private fun flounder(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_FLOUNDER)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.FLOUNDER)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.FLOUNDER.peakSeason.toTypedArray())
    }

    private fun horseMackerel(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_HORSE_MACKEREL)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.HORSE_MACKEREL)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.HORSE_MACKEREL.peakSeason.toTypedArray())
    }

    private fun mackerel(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_MACKEREL)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.MACKEREL)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.MACKEREL.peakSeason.toTypedArray())
    }

    private fun monkfish(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_MONKFISH)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.MONKFISH)
            .food(FoodProperties.food().nutrition(3).saturation(0.2f).build())
            .peakSeason(*FishType.MONKFISH.peakSeason.toTypedArray())
    }

    private fun octopus(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_OCTOPUS)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.OCTOPUS)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.OCTOPUS.peakSeason.toTypedArray())
    }

    private fun salmon(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_SALMON)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.SALMON)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SALMON.peakSeason.toTypedArray())
    }

    private fun seaBass(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_SEA_BASS)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.SEA_BASS)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SEA_BASS.peakSeason.toTypedArray())
    }

    private fun seaBream(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_SEA_BREAM)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.SEA_BREAM)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SEA_BREAM.peakSeason.toTypedArray())
    }

    private fun seaUrchin(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_SEA_URCHIN)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.SEA_URCHIN)
            .food(FoodProperties.food().nutrition(2).saturation(0.2f).build())
            .peakSeason(*FishType.SEA_URCHIN.peakSeason.toTypedArray())
    }

    private fun spanishMackerel(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_SPANISH_MACKEREL)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.SPANISH_MACKEREL)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SPANISH_MACKEREL.peakSeason.toTypedArray())
    }

    private fun squid(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_SQUID)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.SQUID)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SQUID.peakSeason.toTypedArray())
    }

    private fun sweetfish(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_SWEETFISH)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.SWEETFISH)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.SWEETFISH.peakSeason.toTypedArray())
    }

    private fun tuna(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_TUNA)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.TUNA)
            .food(FoodProperties.food().nutrition(2).saturation(0.1f).build())
            .peakSeason(*FishType.TUNA.peakSeason.toTypedArray())
    }

    private fun yellowtail(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FishingTranslations.ITEM_VANILIFE_YELLOWTAIL)
            .category(ServerItemCategory.FISH)
            .itemModel(FishingItemModels.YELLOWTAIL)
            .food(FoodProperties.food().nutrition(3).saturation(0.2f).build())
            .peakSeason(*FishType.YELLOWTAIL.peakSeason.toTypedArray())
    }
}
