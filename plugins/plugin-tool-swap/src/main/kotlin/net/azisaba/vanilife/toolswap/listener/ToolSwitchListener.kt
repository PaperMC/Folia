package net.azisaba.vanilife.toolswap.listener

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDamageEvent

internal class ToolSwitchListener(private val enchantment: TypedKey<Enchantment>) : Listener {
    @EventHandler
    fun onBlockDamage(event: BlockDamageEvent) {
        val enchantment = RegistryAccess.registryAccess()
            .getRegistry(RegistryKey.ENCHANTMENT)
            .getOrThrow(enchantment)

        if (!event.itemInHand.containsEnchantment(enchantment)) {
            return
        }

        val slot = (0..8)
            .filter { slot -> event.player.inventory.getItem(slot)?.containsEnchantment(enchantment) == true }
            .maxByOrNull { slot ->
                event.block.getDestroySpeed(event.player.inventory.getItem(slot)!!, true)
            } ?: return

        event.player.inventory.heldItemSlot = slot
    }
}
