package net.azisaba.vanilife.forestry.listener

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.azisaba.vanilife.forestry.CutAllEnchantments
import net.azisaba.vanilife.forestry.cutdown.CutDownAnimator
import net.azisaba.vanilife.forestry.cutdown.CutDownContext
import net.azisaba.vanilife.forestry.finder.TreeFinderRouter
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.EquipmentSlot

internal class ForestryListener(
    private val finderRouter: TreeFinderRouter,
    private val animator: CutDownAnimator,
) : Listener {
    @EventHandler
    suspend fun onBlockBreak(event: BlockBreakEvent) {
        val itemStack = event.player.equipment.getItem(EquipmentSlot.HAND)

        val enchantment = RegistryAccess.registryAccess()
            .getRegistry(RegistryKey.ENCHANTMENT)
            .getOrThrow(CutAllEnchantments.CUT_ALL)

        if (itemStack.containsEnchantment(enchantment) && finderRouter.findApplicableFinders(event.block).isNotEmpty()) {
            val blockData = event.block.blockData
            val detected = finderRouter.find(event.block) ?: return
            event.block.blockData = blockData
            val context = CutDownContext(event.player, event.block, detected)
            animator.animate(context)
        }
    }
}
