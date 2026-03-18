package net.azisaba.vanilife.forestry

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import com.github.shynixn.mccoroutine.folia.registerSuspendingEvents
import net.azisaba.vanilife.forestry.finder.TreeFinder
import net.azisaba.vanilife.forestry.listener.TimberListener
import org.bukkit.event.block.BlockBreakEvent
import org.koin.core.Koin

internal fun Main.setupEventListeners(koin: Koin) {
    server.pluginManager.registerSuspendingEvents(
        TimberListener(TreeFinder.COMPOSITE_DEFAULT, koin.get(), koin.get()),
        this,
        mapOf(
            BlockBreakEvent::class.java to {
                require(it is BlockBreakEvent)
                regionDispatcher(it.block.location)
            }
        ),
    )
}
