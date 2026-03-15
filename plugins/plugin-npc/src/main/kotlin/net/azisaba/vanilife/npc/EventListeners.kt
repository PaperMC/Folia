package net.azisaba.vanilife.npc

import net.azisaba.vanilife.npc.listener.ExperienceTradeListener

internal fun Main.setupEventListeners() {
    server.pluginManager.registerEvents(ExperienceTradeListener(this), this)
}
