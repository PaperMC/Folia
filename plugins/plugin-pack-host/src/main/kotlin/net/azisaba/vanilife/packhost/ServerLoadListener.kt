package net.azisaba.vanilife.packhost

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent

internal class ServerLoadListener(private val service: HostService) : Listener {
    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        if (event.type == ServerLoadEvent.LoadType.STARTUP) {
            service.createPackAndLaunchServer()
        }
    }
}
