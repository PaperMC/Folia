package net.azisaba.vanilife.forestry

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object ForestryTranslations {
    const val ITEM_VANILIFE_SMALL_TREE_STUMP: String = "item.vanilife.small_tree_stump"
    const val ENCHANTMENT_VANILIFE_AUTO_SAPLING: String = "enchantment.vanilife.auto_sapling"
    const val ENCHANTMENT_VANILIFE_TIMBER: String = "enchantment.vanilife.timber"

    fun us(): PackLanguage = mapOf(
        ITEM_VANILIFE_SMALL_TREE_STUMP to Translation.literal("Small Tree Stump"),
        ENCHANTMENT_VANILIFE_AUTO_SAPLING to Translation.literal("Auto Sapling"),
        ENCHANTMENT_VANILIFE_TIMBER to Translation.literal("Timber"),
    )

    fun jp(): PackLanguage = mapOf(
        ITEM_VANILIFE_SMALL_TREE_STUMP to Translation.literal("ちいさな切りカブ"),
        ENCHANTMENT_VANILIFE_AUTO_SAPLING to Translation.literal("植林"),
        ENCHANTMENT_VANILIFE_TIMBER to Translation.literal("一括伐採"),
    )
}
