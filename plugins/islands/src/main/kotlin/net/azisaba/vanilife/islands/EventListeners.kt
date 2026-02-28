package net.azisaba.vanilife.islands

import net.azisaba.vanilife.islands.listener.AsyncPlayerSpawnLocationListener
import org.koin.core.Koin

internal fun Main.setupEventListeners(koin: Koin) {
    server.pluginManager.registerEvents(AsyncPlayerSpawnLocationListener(koin.get()), this)
}