package net.azisaba.vanilife.islands

import net.azisaba.vanilife.islands.persistence.DatabaseIslandRepository
import net.azisaba.vanilife.islands.persistence.IslandRepository
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module

class Main : JavaPlugin() {
    private lateinit var koinApp: KoinApplication

    override fun onEnable() {
        val config = tomlConfig()
        val database = setupDatabase(config.database)
        setupTables(database)

        koinApp = startKoin {
            modules(module {
                single { config }
                single { database }
                single<IslandRepository> { DatabaseIslandRepository(get()) }
                single<IslandAccess> { IslandAccess(get()) }
            })
        }

        setupEventListeners(koinApp.koin)
    }

    override fun onDisable() {
        koinApp.close()
    }
}