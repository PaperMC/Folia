package net.azisaba.vanilife.forestry

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import com.github.shynixn.mccoroutine.folia.registerSuspendingEvents
import net.azisaba.vanilife.forestry.finder.TreeFinder
import net.azisaba.vanilife.forestry.listener.AutoSaplingListener
import net.azisaba.vanilife.forestry.listener.TimberListener
import org.bukkit.event.block.BlockBreakEvent
import org.koin.core.Koin

internal fun Main.setupEventListeners(koin: Koin) {
    server.pluginManager.registerSuspendingEvents(
        AutoSaplingListener(
            finder = TreeFinder.COMPOSITE_DEFAULT,
            plugin = koin.get()
        ),
        this,
        mapOf(
            BlockBreakEvent::class.java to {
                require(it is BlockBreakEvent)
                regionDispatcher(it.block.location)
            }
        ),
    )
    server.pluginManager.registerSuspendingEvents(
        TimberListener(
            enchantment = ForestryEnchantments.OAK_TIMBER,
            finder = TreeFinder.oak(),
            animator = koin.get(),
            plugin = koin.get()
        ),
        this,
        mapOf(
            BlockBreakEvent::class.java to {
                require(it is BlockBreakEvent)
                regionDispatcher(it.block.location)
            }
        ),
    )
    server.pluginManager.registerSuspendingEvents(
        TimberListener(
            enchantment = ForestryEnchantments.SPRUCE_TIMBER,
            finder = TreeFinder.spruce(),
            animator = koin.get(),
            plugin = koin.get()
        ),
        this,
        mapOf(
            BlockBreakEvent::class.java to {
                require(it is BlockBreakEvent)
                regionDispatcher(it.block.location)
            }
        ),
    )
    server.pluginManager.registerSuspendingEvents(
        TimberListener(
            enchantment = ForestryEnchantments.BIRCH_TIMBER,
            finder = TreeFinder.birch(),
            animator = koin.get(),
            plugin = koin.get()
        ),
        this,
        mapOf(
            BlockBreakEvent::class.java to {
                require(it is BlockBreakEvent)
                regionDispatcher(it.block.location)
            }
        ),
    )
    server.pluginManager.registerSuspendingEvents(
        TimberListener(
            enchantment = ForestryEnchantments.JUNGLE_TIMBER,
            finder = TreeFinder.jungle(),
            animator = koin.get(),
            plugin = koin.get()
        ),
        this,
        mapOf(
            BlockBreakEvent::class.java to {
                require(it is BlockBreakEvent)
                regionDispatcher(it.block.location)
            }
        ),
    )
    server.pluginManager.registerSuspendingEvents(
        TimberListener(
            enchantment = ForestryEnchantments.ACACIA_TIMBER,
            finder = TreeFinder.acacia(),
            animator = koin.get(),
            plugin = koin.get()
        ),
        this,
        mapOf(
            BlockBreakEvent::class.java to {
                require(it is BlockBreakEvent)
                regionDispatcher(it.block.location)
            }
        ),
    )
    server.pluginManager.registerSuspendingEvents(
        TimberListener(
            enchantment = ForestryEnchantments.DARK_OAK_TIMBER,
            finder = TreeFinder.darkOak(),
            animator = koin.get(),
            plugin = koin.get()
        ),
        this,
        mapOf(
            BlockBreakEvent::class.java to {
                require(it is BlockBreakEvent)
                regionDispatcher(it.block.location)
            }
        ),
    )
    server.pluginManager.registerSuspendingEvents(
        TimberListener(
            enchantment = ForestryEnchantments.PALE_OAK_TIMBER,
            finder = TreeFinder.paleOak(),
            animator = koin.get(),
            plugin = koin.get()
        ),
        this,
        mapOf(
            BlockBreakEvent::class.java to {
                require(it is BlockBreakEvent)
                regionDispatcher(it.block.location)
            }
        ),
    )
}
