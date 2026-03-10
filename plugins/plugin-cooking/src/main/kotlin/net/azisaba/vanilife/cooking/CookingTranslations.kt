package net.azisaba.vanilife.cooking

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object CookingTranslations {
    const val ITEM_VANILIFE_BAMBOO_SHOOT: String = "item.vanilife.bamboo_shoot"
    const val ITEM_VANILIFE_CLAM: String = "item.vanilife.clam"
    const val ITEM_VANILIFE_FIREFLY_SQUID: String = "item.vanilife.firefly_squid"
    const val ITEM_VANILIFE_SKIPJACK_TUNA: String = "item.vanilife.skipjack_tuna"
    const val ITEM_VANILIFE_SPANISH_MACKEREL: String = "item.vanilife.spanish_mackerel"
    const val ITEM_VANILIFE_TOMATO: String = "item.vanilife.tomato"

    fun us(): PackLanguage = mapOf(
        ITEM_VANILIFE_BAMBOO_SHOOT to Translation.literal("Bamboo Shoot"),
        ITEM_VANILIFE_CLAM to Translation.literal("Clam"),
        ITEM_VANILIFE_FIREFLY_SQUID to Translation.literal("Firefly Squid"),
        ITEM_VANILIFE_SKIPJACK_TUNA to Translation.literal("Skipjack Tuna"),
        ITEM_VANILIFE_SPANISH_MACKEREL to Translation.literal("Spanish Mackerel"),
        ITEM_VANILIFE_TOMATO to Translation.literal("Tomato"),
    )

    fun jp(): PackLanguage = mapOf(
        ITEM_VANILIFE_BAMBOO_SHOOT to Translation.literal("たけのこ"),
        ITEM_VANILIFE_CLAM to Translation.literal("アサリ"),
        ITEM_VANILIFE_FIREFLY_SQUID to Translation.literal("ホタルイカ"),
        ITEM_VANILIFE_SKIPJACK_TUNA to Translation.literal("カツオ"),
        ITEM_VANILIFE_SPANISH_MACKEREL to Translation.literal("サワラ"),
        ITEM_VANILIFE_TOMATO to Translation.literal("トマト"),
    )
}
