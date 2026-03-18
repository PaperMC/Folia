package net.azisaba.vanilife.farming

import net.azisaba.vanilife.farming.listener.AutoReplantListener

internal fun Main.setupEventListeners() {
    server.pluginManager.registerEvents(
        AutoReplantListener(
            enchantment = FarmingEnchantments.BEETROOT_AUTO_REPLANT,
            crop = Crop.BEETROOT,
            plugin = this,
        ), this
    )
    server.pluginManager.registerEvents(
        AutoReplantListener(
            enchantment = FarmingEnchantments.CARROT_AUTO_REPLANT,
            crop = Crop.CARROT,
            plugin = this,
        ), this
    )
    server.pluginManager.registerEvents(
        AutoReplantListener(
            enchantment = FarmingEnchantments.POTATO_AUTO_REPLANT,
            crop = Crop.POTATO,
            plugin = this,
        ), this
    )
    server.pluginManager.registerEvents(
        AutoReplantListener(
            enchantment = FarmingEnchantments.WHEAT_AUTO_REPLANT,
            crop = Crop.WHEAT,
            plugin = this,
        ), this
    )
}
