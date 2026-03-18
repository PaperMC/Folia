package net.azisaba.vanilife.farming

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object FarmingTranslations {
    const val ENCHANTMENT_VANILIFE_BAKED_POTATO_HARVEST: String = "enchantment.vanilife.baked_potato_harvest"
    const val ENCHANTMENT_VANILIFE_BEETROOT_AUTO_REPLANT: String = "enchantment.vanilife.beetroot_auto_replant"
    const val ENCHANTMENT_VANILIFE_CARROT_AUTO_REPLANT: String = "enchantment.vanilife.carrot_auto_replant"
    const val ENCHANTMENT_VANILIFE_NO_CROP_TRAMPLE: String = "enchantment.vanilife.no_crop_trample"
    const val ENCHANTMENT_VANILIFE_POTATO_AUTO_REPLANT: String = "enchantment.vanilife.potato_auto_replant"
    const val ENCHANTMENT_VANILIFE_WHEAT_AUTO_REPLANT: String = "enchantment.vanilife.wheat_auto_replant"

    fun us(): PackLanguage = mapOf(
        ENCHANTMENT_VANILIFE_BAKED_POTATO_HARVEST to Translation.literal("Baked Potato Harvest"),
        ENCHANTMENT_VANILIFE_BEETROOT_AUTO_REPLANT to Translation.literal("Beetroot Auto Replant"),
        ENCHANTMENT_VANILIFE_CARROT_AUTO_REPLANT to Translation.literal("Carrot Auto Replant"),
        ENCHANTMENT_VANILIFE_NO_CROP_TRAMPLE to Translation.literal("No Crop Trample"),
        ENCHANTMENT_VANILIFE_POTATO_AUTO_REPLANT to Translation.literal("Potato Auto Replant"),
        ENCHANTMENT_VANILIFE_WHEAT_AUTO_REPLANT to Translation.literal("Wheat Auto Replant"),
    )

    fun jp(): PackLanguage = mapOf(
        ENCHANTMENT_VANILIFE_BAKED_POTATO_HARVEST to Translation.literal("こんがりポテト"),
        ENCHANTMENT_VANILIFE_BEETROOT_AUTO_REPLANT to Translation.literal("収穫（ビートルート）"),
        ENCHANTMENT_VANILIFE_CARROT_AUTO_REPLANT to Translation.literal("収穫（ニンジン）"),
        ENCHANTMENT_VANILIFE_NO_CROP_TRAMPLE to Translation.literal("畑保護"),
        ENCHANTMENT_VANILIFE_POTATO_AUTO_REPLANT to Translation.literal("収穫（ジャガイモ）"),
        ENCHANTMENT_VANILIFE_WHEAT_AUTO_REPLANT to Translation.literal("収穫（小麦）"),
    )
}
