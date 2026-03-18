package net.azisaba.vanilife.forestry.listener

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.azisaba.vanilife.forestry.ForestryEnchantments
import net.azisaba.vanilife.forestry.finder.TreeFinder
import net.azisaba.vanilife.forestry.timber.TimberAnimator
import net.azisaba.vanilife.forestry.timber.TimberContext
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin

internal class TimberListener(
    private val finder: TreeFinder,
    private val animator: TimberAnimator,
    private val plugin: Plugin,
) : Listener {
    @EventHandler
    suspend fun onBlockBreak(event: BlockBreakEvent) {
        val itemStack = event.player.equipment.getItem(EquipmentSlot.HAND)

        val enchantment = RegistryAccess.registryAccess()
            .getRegistry(RegistryKey.ENCHANTMENT)
            .getOrThrow(ForestryEnchantments.TIMBER)

        val blockState = event.block.state

        if (itemStack.containsEnchantment(enchantment)) {
            val detected = finder.find(blockState, plugin) ?: return

            val context = TimberContext(event.player, itemStack, blockState, detected)
            animator.animate(context)

            if (!event.player.gameMode.isInvulnerable) {
                detected.dropItems(itemStack, event.player, plugin)
            }
        }
    }
}
