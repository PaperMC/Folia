package net.azisaba.vanilife.packhost

import org.bukkit.plugin.java.JavaPlugin

internal class Main : JavaPlugin() {
    override fun onEnable() {
        val hostService = HostService(this)
        server.pluginManager.registerEvents(ServerLoadListener(hostService), this)
    }
}
