package net.azisaba.vanilife.farming

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object FarmingTranslations {
    const val CROP_DROP_BONUS: String = "crop.drop_bonus"
    const val ENCHANTMENT_VANILIFE_BAKED_POTATO_HARVEST: String = "enchantment.vanilife.baked_potato_harvest"
    const val ENCHANTMENT_VANILIFE_BEETROOT_AUTO_REPLANT: String = "enchantment.vanilife.beetroot_auto_replant"
    const val ENCHANTMENT_VANILIFE_CARROT_AUTO_REPLANT: String = "enchantment.vanilife.carrot_auto_replant"
    const val ENCHANTMENT_VANILIFE_NO_CROP_TRAMPLE: String = "enchantment.vanilife.no_crop_trample"
    const val ENCHANTMENT_VANILIFE_POTATO_AUTO_REPLANT: String = "enchantment.vanilife.potato_auto_replant"
    const val ENCHANTMENT_VANILIFE_WHEAT_AUTO_REPLANT: String = "enchantment.vanilife.wheat_auto_replant"
    const val ITEM_VANILIFE_FERTILIZER: String = "item.vanilife.fertilizer"

    fun us(): PackLanguage = mapOf(
        CROP_DROP_BONUS to Translation.literal("Drop Bonus!"),
        ENCHANTMENT_VANILIFE_BAKED_POTATO_HARVEST to Translation.literal("Baked Potato Harvest"),
        ENCHANTMENT_VANILIFE_BEETROOT_AUTO_REPLANT to Translation.literal("Beetroot Auto Replant"),
        ENCHANTMENT_VANILIFE_CARROT_AUTO_REPLANT to Translation.literal("Carrot Auto Replant"),
        ENCHANTMENT_VANILIFE_NO_CROP_TRAMPLE to Translation.literal("No Crop Trample"),
        ENCHANTMENT_VANILIFE_POTATO_AUTO_REPLANT to Translation.literal("Potato Auto Replant"),
        ENCHANTMENT_VANILIFE_WHEAT_AUTO_REPLANT to Translation.literal("Wheat Auto Replant"),
        ITEM_VANILIFE_FERTILIZER to Translation.literal("Fertilizer"),
    )

    fun jp(): PackLanguage = mapOf(
        CROP_DROP_BONUS to Translation.literal("収穫量増加！"),
        ENCHANTMENT_VANILIFE_BAKED_POTATO_HARVEST to Translation.literal("こんがりポテト"),
        ENCHANTMENT_VANILIFE_BEETROOT_AUTO_REPLANT to Translation.literal("収穫（ビートルート）"),
        ENCHANTMENT_VANILIFE_CARROT_AUTO_REPLANT to Translation.literal("収穫（ニンジン）"),
        ENCHANTMENT_VANILIFE_NO_CROP_TRAMPLE to Translation.literal("畑保護"),
        ENCHANTMENT_VANILIFE_POTATO_AUTO_REPLANT to Translation.literal("収穫（ジャガイモ）"),
        ENCHANTMENT_VANILIFE_WHEAT_AUTO_REPLANT to Translation.literal("収穫（小麦）"),
        ITEM_VANILIFE_FERTILIZER  to Translation.literal("肥料"),
    )
}
