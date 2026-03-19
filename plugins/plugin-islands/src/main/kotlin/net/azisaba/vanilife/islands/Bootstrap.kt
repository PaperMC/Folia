package net.azisaba.vanilife.islands

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.event.RegistryEvents
import net.azisaba.vanilife.islands.portal.PortalDialog
import net.azisaba.vanilife.islands.portal.commands.IslandCommand
import net.azisaba.vanilife.islands.portal.commands.ResourceCacheCommand

class Bootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(
            LifecycleEvents.COMMANDS.newHandler { event ->
                event.registrar().register(ResourceCacheCommand.create())
                event.registrar().register(IslandCommand.create())
            },
        )
        context.lifecycleManager.registerEventHandler(
            RegistryEvents.DIALOG.compose().newHandler { event ->
                PortalDialog.register(event)
            },
        )
        context.lifecycleManager.registerEventHandler(
            LifecycleEvents.TAGS.postFlatten(RegistryKey.DIALOG).newHandler { event ->
                PortalDialog.registerTag(event)
            },
        )
    }
}
