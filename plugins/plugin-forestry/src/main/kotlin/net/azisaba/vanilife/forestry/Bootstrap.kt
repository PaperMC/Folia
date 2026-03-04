package net.azisaba.vanilife.forestry

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.registry.event.RegistryEvents

internal class Bootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(
            RegistryEvents.ENCHANTMENT.compose().newHandler(CutAllEnchantments::bootstrap)
        )
    }
}