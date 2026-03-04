package net.azisaba.vanilife.islands

import net.azisaba.packed.font.PackFont
import net.azisaba.packed.font.provider.PackBitmapFontProvider
import net.azisaba.packed.util.CharCodeFactory
import net.azisaba.packed.util.dsl.ResourceScope
import net.kyori.adventure.key.Key
import org.bukkit.plugin.Plugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object IslandsFonts : KoinComponent {
    private val plugin: Plugin by inject()
    val WAVES: Key = Key.key(plugin, "waves")

    fun ResourceScope<PackFont>.bootstrap() {
        WAVES(
            PackFont(
                listOf(
                    PackBitmapFontProvider(
                        key("large_0.png"),
                        listOf("${Waves.LARGE_0}"),
                        767,
                        768,
                    )
                )
            )
        )
    }

    object Waves : CharCodeFactory() {
        val LARGE_0: Char = nextChar()
    }
}
