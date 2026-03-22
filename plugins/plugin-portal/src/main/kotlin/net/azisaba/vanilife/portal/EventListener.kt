package net.azisaba.vanilife.portal

import net.azisaba.vanilife.portal.listener.ExitAnchorListener
import net.azisaba.vanilife.portal.listener.PortalEnterListener
import net.azisaba.vanilife.portal.listener.PortalIgniteListener
import org.koin.core.Koin

fun Main.setupEventListeners(koin: Koin) {
    server.pluginManager.registerEvents(PortalEnterListener(koin.get(), koin.get()), this)
    server.pluginManager.registerEvents(PortalIgniteListener(koin.get(), koin.get()), this)
    server.pluginManager.registerEvents(ExitAnchorListener, this)
}
