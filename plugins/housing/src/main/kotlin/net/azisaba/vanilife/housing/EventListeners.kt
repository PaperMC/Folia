package net.azisaba.vanilife.housing

import net.azisaba.vanilife.housing.listener.AsyncPlayerSpawnLocationListener
import org.koin.core.Koin

internal fun Main.setupEventListeners(koin: Koin) {
    server.pluginManager.registerEvents(AsyncPlayerSpawnLocationListener(koin.get()), this)
}