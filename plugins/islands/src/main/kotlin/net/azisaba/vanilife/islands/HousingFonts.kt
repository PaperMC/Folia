package net.azisaba.vanilife.islands

import net.azisaba.packed.util.CharCodeFactory
import net.kyori.adventure.key.Key
import org.bukkit.plugin.Plugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object HousingFonts : KoinComponent {
    private val plugin: Plugin by inject()

    val WAVES: Key = Key.key(plugin, "waves")

    object WavesCharCodes : CharCodeFactory() {
        val LARGE_0: Char = nextChar()
    }
}
