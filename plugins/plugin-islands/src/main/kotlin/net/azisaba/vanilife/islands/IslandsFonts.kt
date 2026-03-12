package net.azisaba.vanilife.islands

import net.azisaba.packed.PackedKey
import net.azisaba.packed.font
import net.azisaba.packed.font.CharCodeFactory
import net.azisaba.packed.font.PackFont
import net.azisaba.packed.font.provider.PackBitmapFontProvider
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key

object IslandsFonts {
    val WAVES: PackedKey<PackFont> = PackedKey.font(Vanilife.NAMESPACE, "waves")

    fun waves(): PackFont = PackFont(
        listOf(
            PackBitmapFontProvider(
                Key.key(Vanilife.NAMESPACE, "large_0.png"),
                listOf("${Waves.LARGE_0}"),
                767,
                768,
            )
        )
    )

    object Waves : CharCodeFactory() {
        val LARGE_0: Char = nextChar()
    }
}
