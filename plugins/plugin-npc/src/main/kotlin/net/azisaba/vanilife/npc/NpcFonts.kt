package net.azisaba.vanilife.npc

import net.azisaba.packed.PackedKey
import net.azisaba.packed.font
import net.azisaba.packed.font.CharCodeFactory
import net.azisaba.packed.font.PackFont
import net.azisaba.packed.font.provider.PackBitmapFontProvider
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key

object NpcFonts {
    val NPC_ICONS: PackedKey<PackFont> = PackedKey.font(Vanilife.NAMESPACE, "npc_icons")

    fun npcIcons(): PackFont = PackFont(
        listOf(
            PackBitmapFontProvider(
                Key.key(Vanilife.NAMESPACE, "icon/npc/neko.png"),
                listOf("${NpcIcons.NEKO}"),
                8,
                9,
            )
        )
    )

    object NpcIcons : CharCodeFactory() {
        val NEKO: Char = nextChar()
    }
}
