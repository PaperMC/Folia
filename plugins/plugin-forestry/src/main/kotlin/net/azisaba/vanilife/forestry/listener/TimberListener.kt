package net.azisaba.vanilife.forestry.listener

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import net.azisaba.vanilife.forestry.ForestryEnchantments
import net.azisaba.vanilife.forestry.finder.TreeFinder
import net.azisaba.vanilife.forestry.timber.TimberAnimator
import net.azisaba.vanilife.forestry.timber.TimberContext
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.Plugin

internal class TimberListener(
    private val enchantment: TypedKey<Enchantment>,
    private val finder: TreeFinder,
    private val animator: TimberAnimator,
    private val plugin: Plugin,
) : Listener {
    @EventHandler
    suspend fun onBlockBreak(event: BlockBreakEvent) {
        val itemStack = event.player.equipment.itemInMainHand

        val requiredEnchantment = RegistryAccess.registryAccess()
            .getRegistry(RegistryKey.ENCHANTMENT)
            .getOrThrow(enchantment)

        val blockState = event.block.state

        if (itemStack.containsEnchantment(requiredEnchantment)) {
            val detected = finder.find(blockState, plugin) ?: return

            val context = TimberContext(event.player, itemStack, blockState, detected)
            animator.animate(context)

            if (!event.player.gameMode.isInvulnerable) {
                detected.dropItems(itemStack, event.player, plugin)
            }

            val autoSaplingEnchantment = RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)
                .getOrThrow(ForestryEnchantments.AUTO_SAPLING)

            if (itemStack.containsEnchantment(autoSaplingEnchantment)) {
                detected.placeSapling(plugin)
            }
        }
    }
}
