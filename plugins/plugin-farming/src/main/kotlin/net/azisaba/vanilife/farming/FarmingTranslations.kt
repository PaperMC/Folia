package net.azisaba.vanilife.farming

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object FarmingTranslations {
    const val ENCHANTMENT_VANILIFE_AUTO_REPLANT: String = "enchantment.vanilife.auto_replant"

    fun us(): PackLanguage = mapOf(
        ENCHANTMENT_VANILIFE_AUTO_REPLANT to Translation.literal("Auto Replant"),
    )

    fun jp(): PackLanguage = mapOf(
        ENCHANTMENT_VANILIFE_AUTO_REPLANT to Translation.literal("収穫"),
    )
}
