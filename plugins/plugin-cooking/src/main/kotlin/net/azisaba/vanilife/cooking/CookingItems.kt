package net.azisaba.vanilife.cooking

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.event.RegistryComposeEvent
import net.azisaba.vanilife.Season
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.item.ServerItemType
import net.azisaba.vanilife.registry.data.ServerItemTypeRegistryEntry
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component

object CookingItems {
    val BAMBOO_SHOOT: TypedKey<ServerItemType> =
        TypedKey.create(RegistryKey.SERVER_ITEM, Key.key(Vanilife.NAMESPACE, "bamboo_shoot"))
    val CLAM: TypedKey<ServerItemType> =
        TypedKey.create(RegistryKey.SERVER_ITEM, Key.key(Vanilife.NAMESPACE, "clam"))
    val FIREFLY_SQUID: TypedKey<ServerItemType> =
        TypedKey.create(RegistryKey.SERVER_ITEM, Key.key(Vanilife.NAMESPACE, "firefly_squid"))
    val SKIPJACK_TUNA: TypedKey<ServerItemType> =
        TypedKey.create(RegistryKey.SERVER_ITEM, Key.key(Vanilife.NAMESPACE, "skipjack_tuna"))
    val SPANISH_MACKEREL: TypedKey<ServerItemType> =
        TypedKey.create(RegistryKey.SERVER_ITEM, Key.key(Vanilife.NAMESPACE, "spanish_mackerel"))
    val TOMATO: TypedKey<ServerItemType> =
        TypedKey.create(RegistryKey.SERVER_ITEM, Key.key(Vanilife.NAMESPACE, "tomato"))

    fun bootstrap(event: RegistryComposeEvent<ServerItemType, ServerItemTypeRegistryEntry.Builder>) {
        event.registry().register(BAMBOO_SHOOT, ::bambooShoot)
        event.registry().register(CLAM, ::clam)
        event.registry().register(FIREFLY_SQUID, ::fireflySquid)
        event.registry().register(SKIPJACK_TUNA, ::skipjackTuna)
        event.registry().register(SPANISH_MACKEREL, ::spanishMackerel)
        event.registry().register(TOMATO, ::tomato)
    }

    private fun bambooShoot(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_BAMBOO_SHOOT))
            .itemModel(CookingItemModels.BAMBOO_SHOOT_ITEM)
    }

    private fun clam(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_CLAM))
            .itemModel(CookingItemModels.CLAM_ITEM)
    }

    private fun fireflySquid(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_FIREFLY_SQUID))
            .itemModel(CookingItemModels.FIREFLY_SQUID_ITEM)
    }

    private fun skipjackTuna(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SKIPJACK_TUNA))
            .itemModel(CookingItemModels.SKIPJACK_TUNA_ITEM)
    }

    private fun spanishMackerel(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_SPANISH_MACKEREL))
            .itemModel(CookingItemModels.SPANISH_MACKEREL_ITEM)
    }

    private fun tomato(builder: ServerItemTypeRegistryEntry.Builder) {
        builder.displayName(Component.translatable(CookingTranslations.ITEM_VANILIFE_TOMATO))
            .itemModel(CookingItemModels.TOMATO)
            .peakSeason(
                *Season.SUMMER.subSeasons()
            )
    }
}
