package net.azisaba.vanilife.npc.listener

import com.github.shynixn.mccoroutine.folia.launch
import io.papermc.paper.event.player.PlayerPurchaseEvent
import io.papermc.paper.registry.keys.SoundEventKeys
import kotlinx.coroutines.delay
import net.azisaba.vanilife.npc.NpcItems
import net.azisaba.vanilife.npc.trading.clearExperiences
import net.azisaba.vanilife.npc.trading.experienceCost
import net.azisaba.vanilife.npc.trading.updateExperiencePreview
import net.kyori.adventure.sound.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.TradeSelectEvent
import org.bukkit.inventory.MerchantInventory
import org.bukkit.inventory.view.MerchantView
import org.bukkit.plugin.Plugin

internal class ExperienceTradeListener(private val plugin: Plugin) : Listener {
    @EventHandler
    fun onTradeSelect(event: TradeSelectEvent) {
        val player = event.whoClicked as? Player ?: return
        event.inventory.updateExperiencePreview(player, event.merchant.getRecipe(event.index))
        player.updateInventory()
    }

    @EventHandler
    fun onPlayerPurchase(event: PlayerPurchaseEvent) {
        val experienceCost = event.trade.experienceCost()
        if (experienceCost > 0 && !event.player.gameMode.isInvulnerable) {
            event.player.giveExpLevels(-experienceCost)
            event.player.playSound(Sound.sound(SoundEventKeys.BLOCK_ENCHANTMENT_TABLE_USE, Sound.Source.PLAYER, 0.8f, 0.65f))
        }

        val merchantInventory = event.player.openInventory.topInventory as? MerchantInventory ?: return
        plugin.launch {
            delay(50L)
            merchantInventory.updateExperiencePreview(event.player, event.trade)
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val merchantView = event.view as? MerchantView ?: return
        val clicked = merchantView.getItem(event.rawSlot) ?: return
        if (clicked.isOf(NpcItems.EXPERIENCE)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val merchantInventory = event.inventory as? MerchantInventory ?: return
        merchantInventory.clearExperiences()
    }
}
