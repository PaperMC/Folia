package net.azisaba.vanilife.npc

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.event.RegistryComposeEvent
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.item.ServerItem
import net.azisaba.vanilife.npc.recipe.UnreadableRecipe
import net.azisaba.vanilife.registry.data.ServerItemCategory
import net.azisaba.vanilife.registry.data.ServerItemLoreStyle
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component

object NpcItems {
    val CUT_ALL_RECIPE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "cut_all_recipe"))
    val EXPERIENCE: TypedKey<ServerItem> = RegistryKey.SERVER_ITEM.typedKey(Key.key(Vanilife.NAMESPACE, "experience"))

    fun bootstrap(event: RegistryComposeEvent<ServerItem, ServerItemRegistryEntry.Builder>) {
        event.registry().register(CUT_ALL_RECIPE) { builder -> unreadableRecipe(UnreadableRecipe.CUT_ALL, builder) }
        event.registry().register(EXPERIENCE, ::experience)
    }

    private fun experience(builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(NpcTranslations.ITEM_VANILIFE_EXPERIENCE)
            .category(ServerItemCategory.SYSTEM)
            .itemModel(NpcItemModels.EXPERIENCE)
            .loreStyle(ServerItemLoreStyle.emptyStyle())
    }

    private fun unreadableRecipe(unreadableRecipe: UnreadableRecipe, builder: ServerItemRegistryEntry.Builder) {
        builder.translationKey(NpcTranslations.ITEM_VANILIFE_UNREADABLE_RECIPE)
            .describe()
            .category(ServerItemCategory.MATERIAL)
            .loreStyle(
                ServerItemLoreStyle.loreStyle()
                    .then(ServerItemRegistryEntry::described, ServerItemLoreStyle.Part.description())
                    .then(ServerItemLoreStyle.Part.itemCategory())
                    .then { _, builder ->
                        builder.add(Component.translatable(NpcTranslations.ITEM_VANILIFE_UNREADABLE_RECIPE_READABLE_NPC_TYPES))
                            .add(Component.text(unreadableRecipe.reader.icon).font(NpcFonts.NPC_ICONS))
                    }
                    .build()
            )
            .itemModel(NpcItemModels.UNREADABLE_RECIPE)
    }
}
