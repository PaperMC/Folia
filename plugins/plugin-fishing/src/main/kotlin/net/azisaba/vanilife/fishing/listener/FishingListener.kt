package net.azisaba.vanilife.fishing.listener

import io.papermc.paper.event.entity.FishHookStateChangeEvent
import io.papermc.paper.event.player.PlayerArmSwingEvent
import net.azisaba.vanilife.fishing.FishingContext
import net.azisaba.vanilife.fishing.game.FishingGameManager
import org.bukkit.Material
import org.bukkit.entity.FishHook
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.inventory.EquipmentSlot

internal class FishingListener(private val fishingGameManager: FishingGameManager) : Listener {
    @EventHandler
    fun onFishHookStageChange(event: FishHookStateChangeEvent) {
        val fishHook = event.entity
        if (event.newHookState == FishHook.HookState.BOBBING) {
            val player = fishHook.shooter as? Player ?: return
            fishingGameManager.startWaiting(FishingContext(player, fishHook))
        }
    }

    @EventHandler
    fun onPlayerFish(event: PlayerFishEvent) {
        when (event.state) {
            PlayerFishEvent.State.LURED,
            PlayerFishEvent.State.BITE,
            PlayerFishEvent.State.CAUGHT_FISH -> {
                event.isCancelled = true
            }
            else -> {}
        }
    }

    @EventHandler
    fun onPlayerArmSwing(event: PlayerArmSwingEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.player.equipment.itemInMainHand.type != Material.FISHING_ROD) return
        event.isCancelled = fishingGameManager.pullFishHook(event.player)
    }
}
