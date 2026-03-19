package net.azisaba.vanilife.farming.listener

import net.azisaba.vanilife.event.BlockDropLootEvent
import net.azisaba.vanilife.event.BlockRandomTickEvent
import net.azisaba.vanilife.farming.Crop
import net.azisaba.vanilife.farming.block.CropBlockBehaviour
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.plugin.Plugin
import kotlin.random.asKotlinRandom

internal class CropBlockListener(private val behaviour: CropBlockBehaviour, private val plugin: Plugin) : Listener {
    @EventHandler
    fun onBlockRandomTick(event: BlockRandomTickEvent) {
        if (event.block.blockData is Ageable) {
            behaviour.randomTick(event.block, event.random.asKotlinRandom())
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val crop = Crop.byBlock(event.blockPlaced.type) ?: return
        if (crop.isFullyGrownBlock(event.blockPlaced.blockData)) return
        behaviour.blockPlace(event.blockPlaced, crop)
    }

    @EventHandler
    fun onBlockDropLoot(event: BlockDropLootEvent) {
        event.setDrops(
            behaviour.applyDropBonus(
                event.block,
                event.blockState,
                event.entity as? Player,
                event.drops,
                plugin,
            )
        )
    }
}
