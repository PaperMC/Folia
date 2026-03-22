package net.azisaba.vanilife.portal

import net.azisaba.packed.lang.PackLanguage
import net.azisaba.packed.lang.Translation

object PortalTranslations {
    const val DIALOG_VANILIFE_RETURN: String = "dialog.vanilife.return"
    const val DIALOG_VANILIFE_RETURN_CONFIRM: String = "dialog.vanilife.return.confirm"
    const val DIALOG_VANILIFE_RETURN_NO: String = "dialog.vanilife.return.no"
    const val DIALOG_VANILIFE_RETURN_YES: String = "dialog.vanilife.return.yes"

    fun us(): PackLanguage = mapOf(
        DIALOG_VANILIFE_RETURN to Translation.literal("Return to Island"),
        DIALOG_VANILIFE_RETURN_CONFIRM to Translation.literal("Are you really going back to your island?"),
        DIALOG_VANILIFE_RETURN_NO to Translation.literal("Pass for now"),
        DIALOG_VANILIFE_RETURN_YES to Translation.literal("Okay!"),
    )

    fun jp(): PackLanguage = mapOf(
        DIALOG_VANILIFE_RETURN to Translation.literal("島に帰りたい"),
        DIALOG_VANILIFE_RETURN_CONFIRM to Translation.literal("本当に島に戻りますか？"),
        DIALOG_VANILIFE_RETURN_NO to Translation.literal("今はやめとく"),
        DIALOG_VANILIFE_RETURN_YES to Translation.literal("オッケー！"),
    )
}
