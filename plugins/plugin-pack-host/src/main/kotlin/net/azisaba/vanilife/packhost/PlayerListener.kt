package net.azisaba.vanilife.packhost

import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class PlayerListener(private val packInfo: ResourcePackInfo) : Listener {
    @EventHandler
    fun onAsyncPlayerConnectionConfigured(event: AsyncPlayerConnectionConfigureEvent) {
        event.connection.audience.sendResourcePacks(
            ResourcePackRequest.resourcePackRequest()
                .packs(packInfo)
                .replace(false)
                .required(true)
                .build()
        )
    }
}