package net.azisaba.vanilife.islands

import net.azisaba.vanilife.islands.listener.IslandPlayerListener
import net.azisaba.vanilife.islands.portal.listener.BedTrackerListener
import net.azisaba.vanilife.islands.portal.listener.PortalEnterListener
import net.azisaba.vanilife.islands.portal.listener.PortalIgniteListener
import org.koin.core.Koin

internal fun Main.setupEventListeners(koin: Koin) {
    server.pluginManager.registerEvents(IslandPlayerListener(koin.get(), koin.get()), this)
    // server.pluginManager.registerEvents(BedTrackerListener(this, koin.get(), koin.get<Config>().portal.resourceWorld), this)
    // server.pluginManager.registerEvents(PortalIgniteListener(this, koin.get<Config>().portal.resourceWorld, koin.get()), this)
    // server.pluginManager.registerEvents(PortalEnterListener(this, koin.get<Config>().portal.resourceWorld, koin.get()), this)
}
