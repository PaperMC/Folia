package net.azisaba.vanilife.packhost

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object PackHostTranslations {
    fun us(): PackLanguage = mapOf(
        "season.spring" to Translation.literal("Spring"),
        "season.spring.early" to Translation.literal("Early Spring"),
        "season.spring.mid" to Translation.literal("Mid Spring"),
        "season.spring.late" to Translation.literal("Late Spring"),
        "season.summer" to Translation.literal("Summer"),
        "season.summer.early" to Translation.literal("Early Summer"),
        "season.summer.mid" to Translation.literal("Mid Summer"),
        "season.summer.late" to Translation.literal("Late Summer"),
        "season.fall" to Translation.literal("Fall"),
        "season.fall.early" to Translation.literal("Early Fall"),
        "season.fall.mid" to Translation.literal("Mid Fall"),
        "season.fall.late" to Translation.literal("Late Fall"),
        "season.winter" to Translation.literal("Winter"),
        "season.winter.early" to Translation.literal("Early Winter"),
        "season.winter.mid" to Translation.literal("Mid Winter"),
        "season.winter.late" to Translation.literal("Late Winter"),
    )

    fun jp(): PackLanguage = mapOf(
        "season.spring" to Translation.literal("春"),
        "season.spring.early" to Translation.literal("早春"),
        "season.spring.mid" to Translation.literal("仲春"),
        "season.spring.late" to Translation.literal("晩春"),
        "season.summer" to Translation.literal("夏"),
        "season.summer.early" to Translation.literal("初夏"),
        "season.summer.mid" to Translation.literal("仲夏"),
        "season.summer.late" to Translation.literal("晩夏"),
        "season.fall" to Translation.literal("秋"),
        "season.fall.early" to Translation.literal("初秋"),
        "season.fall.mid" to Translation.literal("仲秋"),
        "season.fall.late" to Translation.literal("晩秋"),
        "season.winter" to Translation.literal("冬"),
        "season.winter.early" to Translation.literal("初冬"),
        "season.winter.mid" to Translation.literal("仲冬"),
        "season.winter.late" to Translation.literal("晩冬"),
    )
}
