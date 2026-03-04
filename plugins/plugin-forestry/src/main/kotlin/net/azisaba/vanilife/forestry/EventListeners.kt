package net.azisaba.vanilife.forestry

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import com.github.shynixn.mccoroutine.folia.registerSuspendingEvents
import net.azisaba.vanilife.forestry.listener.ForestryListener
import org.bukkit.event.block.BlockBreakEvent
import org.koin.core.Koin

internal fun Main.setupEventListeners(koin: Koin) {
    server.pluginManager.registerSuspendingEvents(
        ForestryListener(koin.get(), koin.get()),
        this,
        mapOf(
            BlockBreakEvent::class.java to {
                require(it is BlockBreakEvent)
                regionDispatcher(it.block.location)
            }
        ),
    )
}
