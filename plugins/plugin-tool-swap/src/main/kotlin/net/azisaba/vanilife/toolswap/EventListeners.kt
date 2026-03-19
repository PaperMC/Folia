package net.azisaba.vanilife.toolswap

import net.azisaba.vanilife.toolswap.listener.ToolSwitchListener

internal fun Main.setupEventListeners() {
    server.pluginManager.registerEvents(ToolSwitchListener(ToolSwapEnchantments.TOOL_SWAP), this)
}