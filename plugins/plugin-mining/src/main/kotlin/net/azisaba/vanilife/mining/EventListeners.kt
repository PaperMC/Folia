package net.azisaba.vanilife.mining

import net.azisaba.vanilife.mining.listener.AutoSmeltListener
import net.azisaba.vanilife.mining.listener.ComboListener
import net.azisaba.vanilife.mining.listener.FrozenOreMiningListener
import net.azisaba.vanilife.mining.listener.MinerListener
import net.coreprotect.CoreProtect

internal fun Main.setupEventListeners() {
    server.pluginManager.registerEvents(ComboListener, this)
    server.pluginManager.registerEvents(MinerListener(this), this)
    server.pluginManager.registerEvents(AutoSmeltListener(MiningEnchantments.AUTO_SMELT), this)

    val coreProtectApi = (server.pluginManager.getPlugin("CoreProtect") as CoreProtect).api
    server.pluginManager.registerEvents(
        FrozenOreMiningListener(MiningBiomeTags.DROPS_FROZEN_ORES, coreProtectApi),
        this
    )
}
