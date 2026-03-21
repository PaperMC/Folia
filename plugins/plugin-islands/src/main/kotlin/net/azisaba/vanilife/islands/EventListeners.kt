package net.azisaba.vanilife.islands

import net.azisaba.vanilife.islands.listener.IslandPlayerListener
import org.koin.core.Koin

internal fun Main.setupEventListeners(koin: Koin) {
    server.pluginManager.registerEvents(IslandPlayerListener(koin.get(), koin.get()), this)
}
