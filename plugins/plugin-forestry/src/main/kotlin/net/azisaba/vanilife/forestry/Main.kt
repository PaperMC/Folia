package net.azisaba.vanilife.forestry

import com.github.retrooper.packetevents.PacketEvents
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import me.tofaa.entitylib.APIConfig
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform
import net.azisaba.vanilife.forestry.cutdown.CutDownAnimator
import net.azisaba.vanilife.forestry.finder.TreeFinderRouter
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.module

class Main : JavaPlugin() {
    private lateinit var koinApp: KoinApplication

    override fun onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
        PacketEvents.getAPI().load()
    }

    override fun onEnable() {
        PacketEvents.getAPI().init()
        EntityLib.init(SpigotEntityLibPlatform(this), APIConfig(PacketEvents.getAPI()))

        koinApp = startKoin {
            modules(module {
                single<Plugin> { this@Main }
                single { TreeFinderRouter.build(get()) }
                single { CutDownAnimator() }
            })
        }

        setupEventListeners(koinApp.koin)
    }

    override fun onDisable() {
        koinApp.close()
        PacketEvents.getAPI().terminate()
    }
}
