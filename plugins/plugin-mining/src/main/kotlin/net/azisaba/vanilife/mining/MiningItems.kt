package net.azisaba.vanilife.mining

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.event.RegistryComposeEvent
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.item.ServerItem
import net.azisaba.vanilife.registry.data.ServerItemCategory
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry
import net.kyori.adventure.key.Key

object MiningItems {
    val FROZEN_COAL: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "frozen_coal"))
    val FROZEN_DIAMOND: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "frozen_diamond"))
    val FROZEN_EMERALD: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "frozen_emerald"))
    val FROZEN_LAPIS_LAZULI: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "frozen_lapis_lazuli"))
    val FROZEN_RAW_COPPER: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "frozen_raw_copper"))
    val FROZEN_RAW_GOLD: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "frozen_raw_gold"))
    val FROZEN_RAW_IRON: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "frozen_raw_iron"))
    val FROZEN_REDSTONE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "frozen_redstone"))

    fun bootstrap(event: RegistryComposeEvent<ServerItem, ServerItemRegistryEntry.Builder>) {
        event.registry().register(FROZEN_COAL, ::frozenCoal)
        event.registry().register(FROZEN_DIAMOND, ::frozenDiamond)
        event.registry().register(FROZEN_EMERALD, ::frozenEmerald)
        event.registry().register(FROZEN_LAPIS_LAZULI, ::frozenLapisLazuli)
        event.registry().register(FROZEN_RAW_COPPER, ::frozenRawCopper)
        event.registry().register(FROZEN_RAW_GOLD, ::frozenRawGold)
        event.registry().register(FROZEN_RAW_IRON, ::frozenRawIron)
        event.registry().register(FROZEN_REDSTONE, ::frozenRedstone)
    }

    private fun frozenCoal(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(MiningTranslations.ITEM_VANILIFE_FROZEN_COAL)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(MiningItemModels.FROZEN_COAL)
    }

    private fun frozenDiamond(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(MiningTranslations.ITEM_VANILIFE_FROZEN_DIAMOND)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(MiningItemModels.FROZEN_DIAMOND)
    }

    private fun frozenEmerald(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(MiningTranslations.ITEM_VANILIFE_FROZEN_EMERALD)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(MiningItemModels.FROZEN_EMERALD)
    }

    private fun frozenLapisLazuli(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(MiningTranslations.ITEM_VANILIFE_FROZEN_LAPIS_LAZULI)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(MiningItemModels.FROZEN_LAPIS_LAZULI)
    }

    private fun frozenRawCopper(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(MiningTranslations.ITEM_VANILIFE_FROZEN_RAW_COPPER)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(MiningItemModels.FROZEN_RAW_COPPER)
    }

    private fun frozenRawGold(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(MiningTranslations.ITEM_VANILIFE_FROZEN_RAW_GOLD)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(MiningItemModels.FROZEN_RAW_GOLD)
    }

    private fun frozenRawIron(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(MiningTranslations.ITEM_VANILIFE_FROZEN_RAW_IRON)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(MiningItemModels.FROZEN_RAW_IRON)
    }

    private fun frozenRedstone(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(MiningTranslations.ITEM_VANILIFE_FROZEN_REDSTONE)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(MiningItemModels.FROZEN_REDSTONE)
    }
}
