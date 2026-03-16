package net.azisaba.vanilife.mining

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object MiningTranslations {
    const val ENCHANTMENT_VANILIFE_RANGE_MINING_3X3X3: String = "enchantment.vanilife.range_mining_3x3x3"
    const val ENCHANTMENT_VANILIFE_RANGE_MINING_5X5X5: String = "enchantment.vanilife.range_mining_5x5x5"
    const val ENCHANTMENT_VANILIFE_VERTICAL_MINING_DOWN_1: String = "enchantment.vanilife.vertical_mining_down_1"
    const val ENCHANTMENT_VANILIFE_VERTICAL_MINING_UP_1: String = "enchantment.vanilife.vertical_mining_up_1"
    const val ENCHANTMENT_VANILIFE_VERTICAL_MINING_UP_DOWN_1: String = "enchantment.vanilife.vertical_mining_up_down_1"
    const val ITEM_VANILIFE_FROZEN_COAL: String = "item.vanilife.frozen_coal"
    const val ITEM_VANILIFE_FROZEN_DIAMOND: String = "item.vanilife.frozen_diamond"
    const val ITEM_VANILIFE_FROZEN_EMERALD: String = "item.vanilife.frozen_emerald"
    const val ITEM_VANILIFE_FROZEN_LAPIS_LAZULI: String = "item.vanilife.frozen_lapis_lazuli"
    const val ITEM_VANILIFE_FROZEN_RAW_COPPER: String = "item.vanilife.frozen_raw_copper"
    const val ITEM_VANILIFE_FROZEN_RAW_GOLD: String = "item.vanilife.frozen_raw_gold"
    const val ITEM_VANILIFE_FROZEN_RAW_IRON: String = "item.vanilife.frozen_raw_iron"
    const val ITEM_VANILIFE_FROZEN_REDSTONE: String = "item.vanilife.frozen_redstone"

    fun us(): PackLanguage = mapOf(
        ENCHANTMENT_VANILIFE_RANGE_MINING_3X3X3 to Translation.literal("Range Mining (3x3x3)"),
        ENCHANTMENT_VANILIFE_RANGE_MINING_5X5X5 to Translation.literal("Range Mining (5x5x5)"),
        ENCHANTMENT_VANILIFE_VERTICAL_MINING_DOWN_1 to Translation.literal("Vertical Mining (+1 Down)"),
        ENCHANTMENT_VANILIFE_VERTICAL_MINING_UP_1 to Translation.literal("Vertical Mining (+1 Up)"),
        ENCHANTMENT_VANILIFE_VERTICAL_MINING_UP_DOWN_1 to Translation.literal("Vertical Mining (+1 Up/Down)"),
        ITEM_VANILIFE_FROZEN_COAL to Translation.literal("Frozen Coal"),
        ITEM_VANILIFE_FROZEN_DIAMOND to Translation.literal("Frozen Diamond"),
        ITEM_VANILIFE_FROZEN_EMERALD to Translation.literal("Frozen Emerald"),
        ITEM_VANILIFE_FROZEN_LAPIS_LAZULI to Translation.literal("Frozen Lapis Lazuli"),
        ITEM_VANILIFE_FROZEN_RAW_COPPER to Translation.literal("Frozen Raw Copper"),
        ITEM_VANILIFE_FROZEN_RAW_GOLD to Translation.literal("Frozen Raw Gold"),
        ITEM_VANILIFE_FROZEN_RAW_IRON to Translation.literal("Frozen Raw Iron"),
        ITEM_VANILIFE_FROZEN_REDSTONE to Translation.literal("Frozen Redstone Dust"),
    )

    fun jp(): PackLanguage = mapOf(
        ENCHANTMENT_VANILIFE_RANGE_MINING_3X3X3 to Translation.literal("範囲採掘（3x3x3）"),
        ENCHANTMENT_VANILIFE_RANGE_MINING_5X5X5 to Translation.literal("範囲採掘（5x5x5）"),
        ENCHANTMENT_VANILIFE_VERTICAL_MINING_DOWN_1 to Translation.literal("縦採掘（下1）"),
        ENCHANTMENT_VANILIFE_VERTICAL_MINING_UP_1 to Translation.literal("縦採掘（上1）"),
        ENCHANTMENT_VANILIFE_VERTICAL_MINING_UP_DOWN_1 to Translation.literal("縦採掘（上下1）"),
        ITEM_VANILIFE_FROZEN_COAL to Translation.literal("凍った石炭"),
        ITEM_VANILIFE_FROZEN_DIAMOND to Translation.literal("凍ったダイヤモンド"),
        ITEM_VANILIFE_FROZEN_EMERALD to Translation.literal("凍ったエメラルド"),
        ITEM_VANILIFE_FROZEN_LAPIS_LAZULI to Translation.literal("凍ったラピスラズリ"),
        ITEM_VANILIFE_FROZEN_RAW_COPPER to Translation.literal("凍った銅の原石"),
        ITEM_VANILIFE_FROZEN_RAW_GOLD to Translation.literal("凍った金の原石"),
        ITEM_VANILIFE_FROZEN_RAW_IRON to Translation.literal("凍った鉄の原石"),
        ITEM_VANILIFE_FROZEN_REDSTONE to Translation.literal("凍ったレッドストーンダスト"),
    )
}
