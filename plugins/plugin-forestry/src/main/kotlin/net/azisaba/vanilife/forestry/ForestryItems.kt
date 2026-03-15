package net.azisaba.vanilife.forestry

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.event.RegistryComposeEvent
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.item.ServerItem
import net.azisaba.vanilife.registry.data.ServerItemCategory
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry
import net.kyori.adventure.key.Key

object ForestryItems {
    val SMALL_TREE_STUMP: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "small_tree_stump"))

    fun bootstrap(event: RegistryComposeEvent<ServerItem, ServerItemRegistryEntry.Builder>) {
        event.registry().register(SMALL_TREE_STUMP, ::smallTreeStump)
    }

    private fun smallTreeStump(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(ForestryTranslations.ITEM_VANILIFE_SMALL_TREE_STUMP)
            .category(ServerItemCategory.MATERIAL)
            .itemModel(ForestryItemModels.SMALL_TREE_STUMP)
    }
}
