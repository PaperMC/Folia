package net.azisaba.vanilife.npc

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object NpcTranslations {
    const val ITEM_VANILIFE_UNREADABLE_RECIPE: String = "item.vanilife.unreadable_recipe"
    const val ITEM_VANILIFE_UNREADABLE_RECIPE_DESCRIPTION: String = "item.vanilife.unreadable_recipe.description"
    const val ITEM_VANILIFE_UNREADABLE_RECIPE_READABLE_NPC_TYPES: String = "item.vanilife.unreadable_recipe.readable_npc_types"

    fun us(): PackLanguage = mapOf(
        ITEM_VANILIFE_UNREADABLE_RECIPE to Translation.literal("Unreadable Recipe"),
        ITEM_VANILIFE_UNREADABLE_RECIPE_DESCRIPTION to Translation.literal("Hmm. It's written in a script I've never seen before."),
        ITEM_VANILIFE_UNREADABLE_RECIPE_READABLE_NPC_TYPES to Translation.literal("Maybe he can read it...?:"),
    )

    fun jp(): PackLanguage = mapOf(
        ITEM_VANILIFE_UNREADABLE_RECIPE to Translation.literal("読めないレシピ"),
        ITEM_VANILIFE_UNREADABLE_RECIPE_DESCRIPTION to Translation.literal("うーん。見たことない字で書かれてる。"),
        ITEM_VANILIFE_UNREADABLE_RECIPE_READABLE_NPC_TYPES to Translation.literal("読めるかも："),
    )
}
