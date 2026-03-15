package net.azisaba.vanilife.mining

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.event.RegistryEvents

class Bootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(
            LifecycleEvents.TAGS.postFlatten(RegistryKey.BLOCK).newHandler(MiningBlockTypeTagKeys::postFlatten)
        )

        context.lifecycleManager.registerEventHandler(
            RegistryEvents.ENCHANTMENT.compose().newHandler(MiningEnchantments::bootstrap)
        )

        context.lifecycleManager.registerEventHandler(
            RegistryEvents.SERVER_ITEM.compose().newHandler(MiningItems::bootstrap)
        )
    }
}
