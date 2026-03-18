package net.azisaba.vanilife.farming.listener

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityChangeBlockEvent

internal class NoCropTrampleListener(private val enchantment: TypedKey<Enchantment>) : Listener {
    @EventHandler
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val player = event.entity as? Player ?: return

        val boots = player.equipment.boots ?: return
        val enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).getOrThrow(enchantment)
        if (event.block.type == Material.FARMLAND && event.to == Material.DIRT && boots.containsEnchantment(enchantment)) {
            event.isCancelled = true
            player.spawnParticle(
                Particle.DUST_PLUME,
                event.block.location.add(0.5, 1.5, 0.5),
                8,
                0.18,
                0.18,
                0.18,
                0.12,
            )
        }
    }
}
