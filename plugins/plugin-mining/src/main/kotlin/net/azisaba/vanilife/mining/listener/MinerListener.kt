package net.azisaba.vanilife.mining.listener

import com.github.shynixn.mccoroutine.folia.launch
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.azisaba.vanilife.mining.MiningBlockTypeTagKeys
import net.azisaba.vanilife.mining.miner.Miner
import net.azisaba.vanilife.mining.miner.MinerType
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.Plugin

internal class MinerListener(private val plugin: Plugin) : Listener {
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val minerSourcesTag = RegistryAccess.registryAccess()
            .getRegistry(RegistryKey.BLOCK)
            .getTag(MiningBlockTypeTagKeys.MINER_SOURCES)

        if (!minerSourcesTag.contains(event.block.type.asBlockType()!!.key())) {
            return
        }

        val itemStack = event.player.equipment.itemInMainHand
        val miner = itemStack.enchantments.entries
            .firstNotNullOfOrNull { (enchantment, _) -> MinerType.byEnchantment(enchantment) } ?: return

        val context = Miner.Context(
            event.player,
            event.block,
            if (!event.player.gameMode.isInvulnerable) itemStack else null,
        )

        plugin.launch {
            miner.miner.perform(context, plugin)
        }
    }
}
