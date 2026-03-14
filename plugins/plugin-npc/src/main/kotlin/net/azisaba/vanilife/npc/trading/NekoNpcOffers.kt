package net.azisaba.vanilife.npc.trading

import net.azisaba.vanilife.cooking.CookingItems
import org.bukkit.Material

val NpcOffer.Builtins.NEKO_OFFERS: NpcOffers
    get() = NpcOffers(offerList)

private val offerList: List<NpcOffer> = listOf(
    NpcOffer.itemWithSeasonalDiscount(CookingItems.BANANA to 1..1, Material.JUNGLE_LOG to 12..16),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.BELL_PEPPER to 1..2, Material.AZURE_BLUET to 12..16),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.BLUEBERRY to 1..2, Material.GLOW_BERRIES to 10..15),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.BUCKWHEAT to 6..8, Material.BAMBOO to 22..45),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.CHERRY to 1..2, Material.CHERRY_LOG to 12..16),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.CHILI_PEPPER to 1..2, Material.MAGMA_BLOCK to 12..15),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.COFFEE_BEANS to 6..10, Material.COCOA_BEANS to 14..18),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.CORN to 1..2, Material.GRAVEL to 14..20),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.EGGPLANT to 1..2, Material.LILY_PAD to 14..17),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.GRAPE to 1..2, Material.ALLIUM to 6..12),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.GREEN_ONION to 1..3, Material.CLAY_BALL to 15..16),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.JAPANESE_RADISH to 1..2, Material.ICE to 15..20),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.KIWI to 1..2, Material.PACKED_ICE to 10..15),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.LETTUCE to 1..2, Material.SPRUCE_LOG to 12..16),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.LOTUS_ROOT to 1..2, Material.BLUE_ORCHID to 6..8),
    NpcOffer.item(CookingItems.MELON to 1..1, Material.DIAMOND to 1..2),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.MISO to 1..2, Material.BIRCH_LOG to 12..16),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.NAPPA_CABBAGE to 1..2, Material.SNOWBALL to 15..16),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.ONION to 1..2, Material.POPPY to 8..12),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.ORANGE to 1..2, Material.PEONY to 6..10),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.PEACH to 1..2, Material.DARK_OAK_LOG to 12..16),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.PERSIMMON to 1..2, Material.BROWN_MUSHROOM to 8..12),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.SOYBEANS to 1..2, Material.MELON_SEEDS to 12..14),
    NpcOffer.randomChoice(
        NpcOffer.itemWithSeasonalDiscount(CookingItems.STRAWBERRY to 1..2, Material.RED_TULIP to 10..12),
        NpcOffer.itemWithSeasonalDiscount(CookingItems.STRAWBERRY to 1..2, Material.ORANGE_TULIP to 10..12),
        NpcOffer.itemWithSeasonalDiscount(CookingItems.STRAWBERRY to 1..2, Material.WHITE_TULIP to 10..12),
        NpcOffer.itemWithSeasonalDiscount(CookingItems.STRAWBERRY to 1..2, Material.PINK_TULIP to 10..12),
    ),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.SWEET_POTATO to 1..2, Material.PUMPKIN to 8..12),
    NpcOffer.itemWithSeasonalDiscount(CookingItems.TOMATO to 1..2, Material.OAK_LOG to 12..16),
)
