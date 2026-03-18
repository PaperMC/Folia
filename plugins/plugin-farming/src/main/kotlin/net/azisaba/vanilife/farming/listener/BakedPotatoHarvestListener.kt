package net.azisaba.vanilife.farming.listener

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.keys.SoundEventKeys
import net.azisaba.vanilife.event.BlockDropLootEvent
import net.azisaba.vanilife.farming.Crop
import net.kyori.adventure.sound.Sound
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack

internal class BakedPotatoHarvestListener(private val enchantment: TypedKey<Enchantment>) : Listener {
    @EventHandler
    fun onBlockDropLoot(event: BlockDropLootEvent) {
        val player = event.entity as? Player ?: return
        val itemStack = event.tool ?: return
        val enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).getOrThrow(enchantment)
        if (Crop.POTATO.isFullyGrownBlock(event.blockState.blockData) && itemStack.containsEnchantment(enchantment)) {
            event.drops = listOf(ItemStack.of(Material.BAKED_POTATO, 1))

            player.playSound(Sound.sound(SoundEventKeys.BLOCK_FIRE_EXTINGUISH, Sound.Source.BLOCK, 0.4f, 1.0f))
            player.spawnParticle(
                Particle.POOF,
                event.block.location.add(0.5, 0.5, 0.5),
                8,
                0.2,
                0.2,
                0.2,
                0.02
            )
            if (!player.gameMode.isInvulnerable) {
                player.equipment.itemInMainHand.damage(8, player)
            }
        }
    }
}
