package net.azisaba.vanilife.mining

import net.azisaba.vanilife.mining.listener.ComboListener
import net.azisaba.vanilife.mining.listener.MinerListener

internal fun Main.setupEventListeners() {
    server.pluginManager.registerEvents(ComboListener, this)
    server.pluginManager.registerEvents(MinerListener(this), this)
}
