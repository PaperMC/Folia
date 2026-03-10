package net.azisaba.vanilife.cooking

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.registry.event.RegistryEvents
import net.azisaba.vanilife.cooking.commands.TomatoCommand
import net.azisaba.vanilife.cooking.commands.VGiveCommand

class Bootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(
            RegistryEvents.SERVER_ITEM.compose().newHandler(CookingItems::bootstrap)
        )

        context.lifecycleManager.registerEventHandler(
            LifecycleEvents.COMMANDS.newHandler { event ->
                event.registrar().register(TomatoCommand.create())
                event.registrar().register(VGiveCommand.create())
            }
        )
    }
}
