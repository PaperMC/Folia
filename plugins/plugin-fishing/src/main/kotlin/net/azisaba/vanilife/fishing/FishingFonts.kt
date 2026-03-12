package net.azisaba.vanilife.fishing

import net.azisaba.packed.PackedKey
import net.azisaba.packed.font
import net.azisaba.packed.font.CharCodeFactory
import net.azisaba.packed.font.PackFont
import net.azisaba.packed.font.provider.PackBitmapFontProvider
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key

object FishingFonts {
    val FISH_SHADOWS: PackedKey<PackFont> = PackedKey.font(Vanilife.NAMESPACE, "fish_shadows")

    fun fishShadows(): PackFont = PackFont(
        listOf(
            PackBitmapFontProvider(
                Key.key(Vanilife.NAMESPACE, "fish_shadow.png"),
                listOf("${FishShadows.FISH_SHADOW}"),
                15,
                16,
            )
        )
    )

    object FishShadows : CharCodeFactory() {
        val FISH_SHADOW: Char = nextChar()
    }
}
