package net.azisaba.vanilife.housing

import com.github.retrooper.packetevents.PacketEvents
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import me.tofaa.entitylib.APIConfig
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform
import net.azisaba.vanilife.housing.islands.IslandAccess
import net.azisaba.vanilife.housing.persistence.DatabaseIslandRepository
import net.azisaba.vanilife.housing.persistence.IslandRepository
import net.azisaba.vanilife.housing.waves.setupWaveManager
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module

class Main : JavaPlugin() {
    private lateinit var koinApp: KoinApplication

    override fun onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
        PacketEvents.getAPI().load()
    }

    override fun onEnable() {
        val config = tomlConfig()
        val database = setupDatabase(config.database).setupTables()

        PacketEvents.getAPI().init()
        EntityLib.init(SpigotEntityLibPlatform(this), APIConfig(PacketEvents.getAPI()))

        koinApp = startKoin {
            modules(module {
                single<Plugin> { this@Main }
                single { config }
                single { database }
                single<IslandRepository> { DatabaseIslandRepository(get()) }
                single<IslandAccess> { IslandAccess(get()) }
            })
        }

        setupEventListeners(koinApp.koin)
        setupPacked()
        setupWaveManager()
    }

    override fun onDisable() {
        koinApp.close()
        PacketEvents.getAPI().terminate()
    }
}
