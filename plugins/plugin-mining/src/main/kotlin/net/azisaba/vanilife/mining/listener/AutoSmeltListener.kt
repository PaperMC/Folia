package net.azisaba.vanilife.mining.listener

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import net.azisaba.vanilife.event.BlockDropLootEvent
import net.azisaba.vanilife.mining.OreType
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack

internal class AutoSmeltListener(private val enchantment: TypedKey<Enchantment>) : Listener {
    @EventHandler
    fun onBlockDropLoot(event: BlockDropLootEvent) {
        val enchantment = RegistryAccess.registryAccess()
            .getRegistry(RegistryKey.ENCHANTMENT)
            .getOrThrow(this@AutoSmeltListener.enchantment)

        if (event.tool?.containsEnchantment(enchantment) == true) {
            val oreType = OreType.byBlock(event.blockState)?.takeIf(OreType::hasIngot) ?: return
            event.mapDrops { drop ->
                if (drop.type == oreType.base) {
                    ItemStack.of(oreType.ingot!!, drop.amount)
                } else drop
            }
        }
    }
}
