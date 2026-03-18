package net.azisaba.vanilife.farming.listener

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import kotlinx.coroutines.delay
import net.azisaba.vanilife.event.BlockDropLootEvent
import net.azisaba.vanilife.farming.Crop
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.Plugin

internal class AutoReplantListener(private val enchantment: TypedKey<Enchantment>, private val plugin: Plugin) : Listener {
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).getOrThrow(enchantment)

        val itemStack = event.player.equipment.itemInMainHand

        if (itemStack.containsEnchantment(enchantment)) {
            val crop = Crop.byBlock(event.block.type) ?: return

            plugin.launch(plugin.regionDispatcher(event.block.location)) {
                delay(50L)
                val blockData = crop.unwrapBlock().createBlockData()
                event.block.blockData = blockData
                itemStack.damage(1, event.player)
            }
        }
    }

    @EventHandler
    fun onBlockDropLoot(event: BlockDropLootEvent) {
        val enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).getOrThrow(enchantment)
        if (event.tool?.containsEnchantment(enchantment) == true) {
            val crop = Crop.byBlock(event.blockState.type) ?: return
            event.filterDrops { drop -> drop.type.asItemType() != crop.unwrapSeeds() }
        }
    }
}
