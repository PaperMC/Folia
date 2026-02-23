package net.azisaba.vanilife.islands

import net.azisaba.vanilife.islands.listener.PlayerSpawnListener
import org.koin.core.Koin

internal fun Main.setupEventListeners(koin: Koin) {
    server.pluginManager.registerEvents(PlayerSpawnListener(koin.get()), this)
}