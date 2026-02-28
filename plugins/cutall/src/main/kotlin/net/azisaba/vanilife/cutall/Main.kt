package net.azisaba.vanilife.cutall

import net.azisaba.vanilife.cutall.finder.TreeFinderRouter
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.module

class Main : JavaPlugin() {
    private lateinit var koinApp: KoinApplication

    override fun onEnable() {
        koinApp = startKoin {
            modules(module {
                single<Plugin> { this@Main }
                single { TreeFinderRouter.build(get()) }
            })
        }

        setupEventListeners(koinApp.koin)
    }

    override fun onDisable() {
        koinApp.close()
    }
}
