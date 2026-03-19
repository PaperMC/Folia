package net.azisaba.vanilife.forestry.listener

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.keys.tags.BlockTypeTagKeys
import net.azisaba.vanilife.forestry.ForestryEnchantments
import net.azisaba.vanilife.forestry.finder.TreeFinder
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.Plugin

internal class AutoSaplingListener(private val finder: TreeFinder, private val plugin: Plugin) : Listener {
    @EventHandler
    suspend fun onBlockBreak(event: BlockBreakEvent) {
        val itemStack = event.player.equipment.itemInMainHand

        val requiredEnchantment = RegistryAccess.registryAccess()
            .getRegistry(RegistryKey.ENCHANTMENT)
            .getOrThrow(ForestryEnchantments.AUTO_SAPLING)

        val timberEnchantment = RegistryAccess.registryAccess()
            .getRegistry(RegistryKey.ENCHANTMENT)
            .getOrThrow(ForestryEnchantments.OAK_TIMBER)

        if (!itemStack.containsEnchantment(requiredEnchantment) || itemStack.containsEnchantment(timberEnchantment)) {
            return
        }

        val dirt = RegistryAccess.registryAccess().getRegistry(RegistryKey.BLOCK).getTag(BlockTypeTagKeys.DIRT)
        val below = event.block.getRelative(BlockFace.DOWN)

        if (dirt.contains(below.type.asBlockType()!!.key())) {
            val detected = finder.find(event.block.state, plugin) ?: return
            detected.placeSapling(plugin)
        }
    }
}
