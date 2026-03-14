package net.azisaba.vanilife.packhost

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation
import net.azisaba.vanilife.Season
import net.azisaba.vanilife.registry.data.ServerItemCategory

object GeneralTranslations {
    const val ITEM_VANILIFE_CATEGORY: String = "item.vanilife.category"
    const val ITEM_VANILIFE_PEAK_SEASON: String = "item.vanilife.peak_season"
    const val ITEM_VANILIFE_PEAK_SEASON_RANGE_MULTIPLE: String = "item.vanilife.peak_season.range.multiple"
    const val ITEM_VANILIFE_PEAK_SEASON_RANGE_SINGLE: String = "item.vanilife.peak_season.range.single"

    fun us(): PackLanguage = mapOf(
        ITEM_VANILIFE_CATEGORY to Translation.literal("Item Category:"),
        ServerItemCategory.MATERIAL.translationKey() to Translation.literal("Material"),
        ServerItemCategory.TOOL.translationKey() to Translation.literal("Tool"),
        ServerItemCategory.EQUIPMENT.translationKey() to Translation.literal("equipment"),
        ServerItemCategory.VEGETABLE.translationKey() to Translation.literal("Vegetable"),
        ServerItemCategory.FRUIT.translationKey() to Translation.literal("Fruit"),
        ServerItemCategory.FISH.translationKey() to Translation.literal("Fish"),
        ServerItemCategory.FOOD.translationKey() to Translation.literal("Food"),
        ServerItemCategory.DRINK.translationKey() to Translation.literal("Drink"),
        ServerItemCategory.DESSERT.translationKey() to Translation.literal("Dessert"),

        ITEM_VANILIFE_PEAK_SEASON to Translation.literal("Peak Season:"),
        ITEM_VANILIFE_PEAK_SEASON_RANGE_MULTIPLE to Translation.literal("- ") + Translation.placeholder() + Translation.literal("〜") + Translation.placeholder(),
        ITEM_VANILIFE_PEAK_SEASON_RANGE_SINGLE to Translation.literal("- ") + Translation.placeholder(),

        Season.SPRING.translationKey() to Translation.literal("Spring"),
        Season.SPRING.withStage(Season.Stage.EARLY).translationKey() to Translation.literal("Early Spring"),
        Season.SPRING.withStage(Season.Stage.MID).translationKey() to Translation.literal("Mid Spring"),
        Season.SPRING.withStage(Season.Stage.LATE).translationKey() to Translation.literal("Late Spring"),
        Season.SUMMER.translationKey() to Translation.literal("Summer"),
        Season.SUMMER.withStage(Season.Stage.EARLY).translationKey() to Translation.literal("Early Summer"),
        Season.SUMMER.withStage(Season.Stage.MID).translationKey() to Translation.literal("Mid Summer"),
        Season.SUMMER.withStage(Season.Stage.LATE).translationKey() to Translation.literal("Late Summer"),
        Season.FALL.translationKey() to Translation.literal("Fall"),
        Season.FALL.withStage(Season.Stage.EARLY).translationKey() to Translation.literal("Early Fall"),
        Season.FALL.withStage(Season.Stage.MID).translationKey() to Translation.literal("Mid Fall"),
        Season.FALL.withStage(Season.Stage.LATE).translationKey() to Translation.literal("Late Fall"),
        Season.WINTER.translationKey() to Translation.literal("Winter"),
        Season.WINTER.withStage(Season.Stage.EARLY).translationKey() to Translation.literal("Early Winter"),
        Season.WINTER.withStage(Season.Stage.MID).translationKey() to Translation.literal("Mid Winter"),
        Season.WINTER.withStage(Season.Stage.LATE).translationKey() to Translation.literal("Late Winter"),
    )

    fun jp(): PackLanguage = mapOf(
        ITEM_VANILIFE_CATEGORY to Translation.literal("カテゴリー："),
        ServerItemCategory.MATERIAL.translationKey() to Translation.literal("素材"),
        ServerItemCategory.TOOL.translationKey() to Translation.literal("ツール"),
        ServerItemCategory.EQUIPMENT.translationKey() to Translation.literal("装備品"),
        ServerItemCategory.VEGETABLE.translationKey() to Translation.literal("野菜"),
        ServerItemCategory.FRUIT.translationKey() to Translation.literal("果物"),
        ServerItemCategory.FISH.translationKey() to Translation.literal("魚"),
        ServerItemCategory.FOOD.translationKey() to Translation.literal("食べ物"),
        ServerItemCategory.DRINK.translationKey() to Translation.literal("飲み物"),
        ServerItemCategory.DESSERT.translationKey() to Translation.literal("デザート"),

        ITEM_VANILIFE_PEAK_SEASON to Translation.literal("旬の時期："),
        ITEM_VANILIFE_PEAK_SEASON_RANGE_MULTIPLE to Translation.literal("・") + Translation.placeholder() + Translation.literal("から") + Translation.placeholder(),
        ITEM_VANILIFE_PEAK_SEASON_RANGE_SINGLE to Translation.literal("・") + Translation.placeholder(),

        Season.SPRING.translationKey() to Translation.literal("春"),
        Season.SPRING.withStage(Season.Stage.EARLY).translationKey() to Translation.literal("早春"),
        Season.SPRING.withStage(Season.Stage.MID).translationKey() to Translation.literal("仲春"),
        Season.SPRING.withStage(Season.Stage.LATE).translationKey() to Translation.literal("晩春"),
        Season.SUMMER.translationKey() to Translation.literal("夏"),
        Season.SUMMER.withStage(Season.Stage.EARLY).translationKey() to Translation.literal("初夏"),
        Season.SUMMER.withStage(Season.Stage.MID).translationKey() to Translation.literal("仲夏"),
        Season.SUMMER.withStage(Season.Stage.LATE).translationKey() to Translation.literal("晩夏"),
        Season.FALL.translationKey() to Translation.literal("秋"),
        Season.FALL.withStage(Season.Stage.EARLY).translationKey() to Translation.literal("初秋"),
        Season.FALL.withStage(Season.Stage.MID).translationKey() to Translation.literal("仲秋"),
        Season.FALL.withStage(Season.Stage.LATE).translationKey() to Translation.literal("晩秋"),
        Season.WINTER.translationKey() to Translation.literal("冬"),
        Season.WINTER.withStage(Season.Stage.EARLY).translationKey() to Translation.literal("初冬"),
        Season.WINTER.withStage(Season.Stage.MID).translationKey() to Translation.literal("仲冬"),
        Season.WINTER.withStage(Season.Stage.LATE).translationKey() to Translation.literal("晩冬"),
    )
}
