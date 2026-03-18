package net.azisaba.vanilife.farming.listener

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import kotlinx.coroutines.delay
import net.azisaba.vanilife.event.BlockDropLootEvent
import net.azisaba.vanilife.farming.Crop
import org.bukkit.Particle
import org.bukkit.block.data.Ageable
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.Plugin

internal class AutoReplantListener(
    private val enchantment: TypedKey<Enchantment>,
    private val crop: Crop,
    private val plugin: Plugin,
) : Listener {
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!crop.isBlock(event.block.type)) return

        if (!crop.isFullyGrownBlock(event.block.blockData)) {
            event.isCancelled = true
            return
        }

        val enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).getOrThrow(enchantment)
        val itemStack = event.player.equipment.itemInMainHand

        if (itemStack.containsEnchantment(enchantment)) {
            plugin.launch(plugin.regionDispatcher(event.block.location)) {
                delay(50L)
                val blockData = crop.unwrapBlock().createBlockData()
                event.block.blockData = blockData
                itemStack.damage(1, event.player)
                event.player.spawnParticle(
                    Particle.HAPPY_VILLAGER,
                    event.block.location.add(0.5, 0.5, 0.5),
                    6,
                    0.18,
                    0.18,
                    0.18,
                    0.12,
                )
            }
        }
    }

    @EventHandler
    fun onBlockDropLoot(event: BlockDropLootEvent) {
        if (crop.isSelfSeeding || !crop.isFullyGrownBlock(event.blockState.blockData)) return

        val enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).getOrThrow(enchantment)

        if (event.tool?.containsEnchantment(enchantment) == true) {
            event.filterDrops { drop -> drop.type.asItemType() != crop.unwrapSeeds() }
        }
    }
}
