package net.azisaba.vanilife.packhost

import net.azisaba.packed.util.dsl.NamespaceScope
import org.bukkit.plugin.Plugin

interface ResourcePlugin : Plugin {
    fun NamespaceScope.pack()
}
