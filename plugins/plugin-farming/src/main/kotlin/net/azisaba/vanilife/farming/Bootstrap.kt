package net.azisaba.vanilife.farming

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.registry.event.RegistryEvents

class Bootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(
            RegistryEvents.ENCHANTMENT.compose().newHandler(FarmingEnchantments::bootstrap)
        )
        context.lifecycleManager.registerEventHandler(
            RegistryEvents.SERVER_ITEM.compose().newHandler(FarmingItems::bootstrap)
        )
    }
}
