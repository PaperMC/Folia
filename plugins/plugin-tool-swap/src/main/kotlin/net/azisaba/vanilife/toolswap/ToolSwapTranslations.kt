package net.azisaba.vanilife.toolswap

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object ToolSwapTranslations {
    const val ENCHANTMENT_VANILIFE_TOOL_SWAP: String = "enchantment.vanilife.tool_swap"

    fun us(): PackLanguage = mapOf(
        ENCHANTMENT_VANILIFE_TOOL_SWAP to Translation.literal("Tool Swap"),
    )

    fun jp(): PackLanguage = mapOf(
        ENCHANTMENT_VANILIFE_TOOL_SWAP to Translation.literal("ツールスワップ"),
    )
}
