package net.azisaba.vanilife.npc

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.azisaba.vanilife.npc.commands.NpcTestCommand

class Bootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS.newHandler { event ->
            event.registrar().register(NpcTestCommand.create())
        })
    }
}