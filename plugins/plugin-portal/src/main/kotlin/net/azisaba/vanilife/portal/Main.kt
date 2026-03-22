package net.azisaba.vanilife.portal

import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.portal.exits.ExitForcer
import net.azisaba.vanilife.portal.finder.PortalFinder
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
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
                    single {
                        PortalFinder(
                            frame = Material.PRISMARINE,
                            allowedInnerWidth = 2..21,
                            allowedInnerHeight = 3..21
                        )
                    }
                    single {
                        ExitForcer(
                            world = Bukkit.getWorld(Key.key(Vanilife.NAMESPACE, "2026/spring"))!!,
                            baseRadius = 8,
                            radiusVariance = 56,
                            resourceCellSpacing = 128,
                            safeSearchRadius = 8,
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
