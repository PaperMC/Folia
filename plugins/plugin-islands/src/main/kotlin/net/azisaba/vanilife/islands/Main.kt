package net.azisaba.vanilife.islands

import com.github.retrooper.packetevents.PacketEvents
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import me.tofaa.entitylib.APIConfig
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform
import net.azisaba.vanilife.islands.portal.ExposedLastBedRepository
import net.azisaba.vanilife.islands.portal.ExposedResourceSpawnRepository
import net.azisaba.vanilife.islands.portal.LastBedRepository
import net.azisaba.vanilife.islands.portal.LastBedStorage
import net.azisaba.vanilife.islands.portal.ResourceSpawnCache
import net.azisaba.vanilife.islands.portal.ResourceSpawnRepository
import net.azisaba.vanilife.islands.portal.ResourceTeleporter
import net.azisaba.vanilife.islands.storage.DatabaseIslandRepository
import net.azisaba.vanilife.islands.storage.IslandRepository
import org.bukkit.Bukkit
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
                single<IslandManager> { IslandManager(get(), Bukkit.getIslandsWorld(), get()) }
                single<ResourceSpawnRepository> { ExposedResourceSpawnRepository(get()) }
                single<LastBedRepository> { ExposedLastBedRepository(get()) }
                single { ResourceSpawnCache(get(), get<Config>().portal, get()) }
                single { LastBedStorage(get()) }
                single { ResourceTeleporter(get(), get(), get(), get(), get<Config>().portal.resourceWorld) }
            })
        }

        setupEventListeners(koinApp.koin)
    }

    override fun onDisable() {
        koinApp.close()
        PacketEvents.getAPI().terminate()
    }
}
