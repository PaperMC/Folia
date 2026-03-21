package net.azisaba.vanilife.portal

import net.azisaba.vanilife.portal.finder.PortalFinder
import net.azisaba.vanilife.portal.listener.PortalIgniteListener
import org.bukkit.plugin.Plugin
import org.koin.core.Koin

fun Main.setupEventListeners(koin: Koin) {
    server.pluginManager.registerEvents(PortalIgniteListener(koin.get<PortalFinder>(), koin.get<Plugin>()), this)
}
