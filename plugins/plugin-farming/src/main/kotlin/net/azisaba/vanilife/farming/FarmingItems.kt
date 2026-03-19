package net.azisaba.vanilife.farming

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.event.RegistryComposeEvent
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.item.ServerItem
import net.azisaba.vanilife.registry.data.ServerItemCategory
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry
import net.kyori.adventure.key.Key

object FarmingItems {
    val FERTILIZER: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "fertilizer"))

    internal fun bootstrap(event: RegistryComposeEvent<ServerItem, ServerItemRegistryEntry.Builder>) {
        event.registry().register(FERTILIZER, ::fertilizer)
    }

    private fun fertilizer(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(FarmingTranslations.ITEM_VANILIFE_FERTILIZER)
            .category(ServerItemCategory.TOOL)
            .itemModel(FarmingItemModels.FERTILIZER)
    }
}
