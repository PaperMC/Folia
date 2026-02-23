package net.azisaba.vanilife

import net.azisaba.vanilife.islands.ExposedIslandRepository
import net.azisaba.vanilife.islands.IslandRepository
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
                single<IslandRepository> { ExposedIslandRepository(get()) }
            })
        }
    }

    override fun onDisable() {
        koinApp.close()
    }
}
