package net.azisaba.vanilife.forestry

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object ForestryTranslations {
    const val ITEM_VANILIFE_SMALL_TREE_STUMP: String = "item.vanilife.small_tree_stump"

    fun us(): PackLanguage = mapOf(
        ITEM_VANILIFE_SMALL_TREE_STUMP to Translation.literal("Small Tree Stump"),
    )

    fun jp(): PackLanguage = mapOf(
        ITEM_VANILIFE_SMALL_TREE_STUMP to Translation.literal("ちいさな切りカブ"),
    )
}
