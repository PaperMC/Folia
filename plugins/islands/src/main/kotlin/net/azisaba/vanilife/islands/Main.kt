package net.azisaba.vanilife.islands

import com.github.retrooper.packetevents.PacketEvents
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import me.tofaa.entitylib.APIConfig
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform
import net.azisaba.vanilife.islands.island.IslandManager
import net.azisaba.vanilife.islands.persistence.DatabaseIslandRepository
import net.azisaba.vanilife.islands.persistence.IslandRepository
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
                single<IslandManager> { IslandManager(get(), get()) }
            })
        }

        setupEventListeners(koinApp.koin)
        setupPacked()
    }

    override fun onDisable() {
        koinApp.close()
        PacketEvents.getAPI().terminate()
    }
}
