package net.azisaba.vanilife.packhost

import net.azisaba.packed.PackedKey
import net.azisaba.packed.font
import net.azisaba.packed.font.PackFont
import net.azisaba.packed.font.provider.PackTtfFontProvider
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key

object GeneralFonts {
    val DEFAULT: PackedKey<PackFont> = PackedKey.font(Key.MINECRAFT_NAMESPACE, "default")

    fun default(): PackFont = PackFont(
        listOf(
            PackTtfFontProvider(
                file = Key.key(Vanilife.NAMESPACE, "misaki_gothic.ttf"),
                size = 8,
                oversample = 2,
                skip = (' '..'~').toList(),
            )
        )
    )
}