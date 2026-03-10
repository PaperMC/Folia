package net.azisaba.vanilife.cooking

import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module

class Main : JavaPlugin() {
    private lateinit var koinApp: KoinApplication

    override fun onEnable() {
        koinApp = startKoin {
            modules(module {
                single<Plugin> { this@Main }
            })
        }
    }

    override fun onDisable() {
        koinApp.close()
    }
}
