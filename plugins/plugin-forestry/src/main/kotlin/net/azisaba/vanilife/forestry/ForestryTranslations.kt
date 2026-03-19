package net.azisaba.vanilife.forestry

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object ForestryTranslations {
    const val ENCHANTMENT_VANILIFE_ACACIA_TIMBER: String = "enchantment.vanilife.acacia_timber"
    const val ENCHANTMENT_VANILIFE_AUTO_SAPLING: String = "enchantment.vanilife.auto_sapling"
    const val ENCHANTMENT_VANILIFE_BIRCH_TIMBER: String = "enchantment.vanilife.birch_timber"
    const val ENCHANTMENT_VANILIFE_DARK_OAK_TIMBER: String = "enchantment.vanilife.dark_oak_timber"
    const val ENCHANTMENT_VANILIFE_JUNGLE_TIMBER: String = "enchantment.vanilife.jungle_timber"
    const val ENCHANTMENT_VANILIFE_OAK_TIMBER: String = "enchantment.vanilife.oak_timber"
    const val ENCHANTMENT_VANILIFE_PALE_OAK_TIMBER: String = "enchantment.vanilife.pale_oak_timber"
    const val ENCHANTMENT_VANILIFE_SPRUCE_TIMBER: String = "enchantment.vanilife.spruce_timber"

    fun us(): PackLanguage = mapOf(
        ENCHANTMENT_VANILIFE_ACACIA_TIMBER to Translation.literal("Acacia Timber"),
        ENCHANTMENT_VANILIFE_AUTO_SAPLING to Translation.literal("Auto Sapling"),
        ENCHANTMENT_VANILIFE_BIRCH_TIMBER to Translation.literal("Birch Timber"),
        ENCHANTMENT_VANILIFE_DARK_OAK_TIMBER to Translation.literal("Dark Oak Timber"),
        ENCHANTMENT_VANILIFE_JUNGLE_TIMBER to Translation.literal("Jungle Timber"),
        ENCHANTMENT_VANILIFE_OAK_TIMBER to Translation.literal("Oak Timber"),
        ENCHANTMENT_VANILIFE_PALE_OAK_TIMBER to Translation.literal("Pale Oak Timber"),
        ENCHANTMENT_VANILIFE_SPRUCE_TIMBER to Translation.literal("Spruce Timber"),
    )

    fun jp(): PackLanguage = mapOf(
        ENCHANTMENT_VANILIFE_ACACIA_TIMBER to Translation.literal("木こり（アカシアの木）"),
        ENCHANTMENT_VANILIFE_AUTO_SAPLING to Translation.literal("植林"),
        ENCHANTMENT_VANILIFE_BIRCH_TIMBER to Translation.literal("木こり（シラカバの木）"),
        ENCHANTMENT_VANILIFE_DARK_OAK_TIMBER to Translation.literal("木こり（ダークオーク）"),
        ENCHANTMENT_VANILIFE_JUNGLE_TIMBER to Translation.literal("木こり（ジャングル）"),
        ENCHANTMENT_VANILIFE_OAK_TIMBER to Translation.literal("木こり（オーク）"),
        ENCHANTMENT_VANILIFE_PALE_OAK_TIMBER to Translation.literal("木こり（ペールオーク）"),
        ENCHANTMENT_VANILIFE_SPRUCE_TIMBER to Translation.literal("木こり（トウヒ）"),
    )
}
