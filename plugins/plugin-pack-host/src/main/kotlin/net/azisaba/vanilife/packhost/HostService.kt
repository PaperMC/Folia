package net.azisaba.vanilife.packhost

import net.azisaba.packed.util.dsl.packed
import net.azisaba.packed.util.ktor.ResourcePackRequestSender
import net.azisaba.packed.util.ktor.launchKtor
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

internal class HostService(private val plugin: Plugin) {
    fun createPackAndLaunchServer() {
        val resourcePlugins = collectResourcePlugins()
        val pack = createPack(resourcePlugins)

        pack.launchKtor(
            plugin,
            port = 8085,
            resourcePackRequestSender = ResourcePackRequestSender.Simple(),
        )
    }

    private fun collectResourcePlugins(): List<ResourcePlugin> =
        Bukkit.getPluginManager().plugins.filterIsInstance<ResourcePlugin>()

    private fun createPack(resourcePlugins: List<ResourcePlugin>) = packed {
        metadata {
            minFormat(75)
            maxFormat(75)
            describe(Component.text(plugin.pluginMeta.displayName))
        }

        plugins {
            resourcePlugins.forEach { plugin ->
                +plugin
            }
        }

        resourcePlugins.forEach { plugin ->
            namespace(plugin) {
                with(plugin) { pack() }
            }
        }
    }
}
