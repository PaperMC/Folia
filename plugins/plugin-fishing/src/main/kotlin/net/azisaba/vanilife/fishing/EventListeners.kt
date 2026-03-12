package net.azisaba.vanilife.fishing

import net.azisaba.vanilife.fishing.game.FishingGameManager
import net.azisaba.vanilife.fishing.listener.FishingListener

internal fun Main.setupEventListeners() {
    server.pluginManager.registerEvents(FishingListener(FishingGameManager(this)), this)
}
