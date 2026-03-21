package net.azisaba.vanilife.portal

import net.azisaba.vanilife.portal.finder.PortalFinder
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module

class Main : JavaPlugin() {
    private lateinit var koinApp: KoinApplication

    override fun onEnable() {
        koinApp = startKoin {
            modules(
                module {
                    single<Plugin> { this@Main }
                    single<PortalFinder> {
                        PortalFinder(
                            frame = Material.PRISMARINE,
                            allowedInnerWidth = 2..21,
                            allowedInnerHeight = 3..21
                        )
                    }
                }
            )
        }

        setupEventListeners(koinApp.koin)
    }

    override fun onDisable() {
        koinApp.close()
    }
}
