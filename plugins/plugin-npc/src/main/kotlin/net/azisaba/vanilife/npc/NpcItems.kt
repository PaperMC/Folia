package net.azisaba.vanilife.npc

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.event.RegistryComposeEvent
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.item.ServerItem
import net.azisaba.vanilife.npc.recipe.UnreadableRecipe
import net.azisaba.vanilife.registry.data.ServerItemCategory
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry
import net.kyori.adventure.key.Key

object NpcItems {
    val CUT_ALL_UNREADABLE_RECIPE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "cut_all_unreadable_recipe"))
    val UNREADABLE_RECIPE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "unreadable_recipe"))

    fun bootstrap(event: RegistryComposeEvent<ServerItem, ServerItemRegistryEntry.Builder>) {
        event.registry().register(UNREADABLE_RECIPE, ::unreadableRecipe)
        event.registry().register(CUT_ALL_UNREADABLE_RECIPE, UnreadableRecipe.CUT_ALL::recipeItem)
    }

    private fun unreadableRecipe(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(NpcTranslations.ITEM_VANILIFE_UNREADABLE_RECIPE)
            .describe()
            .category(ServerItemCategory.MATERIAL)
            .itemModel(NpcItemModels.UNREADABLE_RECIPE)
    }
}
