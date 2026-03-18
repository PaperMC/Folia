package net.azisaba.vanilife.farming

import net.azisaba.vanilife.farming.listener.AutoReplantListener

internal fun Main.setupEventListeners() {
    server.pluginManager.registerEvents(AutoReplantListener(FarmingEnchantments.AUTO_REPLANT, this), this)
}
