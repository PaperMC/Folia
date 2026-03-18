package net.azisaba.vanilife.toolswap

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.event.RegistryEvents

class Bootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(
            LifecycleEvents.TAGS.postFlatten(RegistryKey.ITEM).newHandler(ToolSwapItemTypeTags::postFlatten)
        )
        context.lifecycleManager.registerEventHandler(
            RegistryEvents.ENCHANTMENT.compose().newHandler(ToolSwapEnchantments::bootstrap)
        )
    }
}
