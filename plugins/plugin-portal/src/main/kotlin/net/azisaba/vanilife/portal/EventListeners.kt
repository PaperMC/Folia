package net.azisaba.vanilife.portal

import net.azisaba.vanilife.portal.listener.IgniteListener

internal fun Main.setupEventListeners() {
    server.pluginManager.registerEvents(IgniteListener(this), this)
}