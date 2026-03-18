package net.azisaba.vanilife.forestry

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object ForestryTranslations {
    const val ITEM_VANILIFE_SMALL_TREE_STUMP: String = "item.vanilife.small_tree_stump"
    const val ENCHANTMENT_VANILIFE_TIMBER: String = "enchantment.vanilife.cut_all"

    fun us(): PackLanguage = mapOf(
        ITEM_VANILIFE_SMALL_TREE_STUMP to Translation.literal("Small Tree Stump"),
        ENCHANTMENT_VANILIFE_TIMBER to Translation.literal("Timber"),
    )

    fun jp(): PackLanguage = mapOf(
        ITEM_VANILIFE_SMALL_TREE_STUMP to Translation.literal("ちいさな切りカブ"),
        ENCHANTMENT_VANILIFE_TIMBER to Translation.literal("一括伐採"),
    )
}
