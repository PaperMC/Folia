package net.azisaba.vanilife.npc.ai.goal

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import kr.toxicity.model.api.bukkit.platform.BukkitPlayer
import kr.toxicity.model.api.event.hitbox.HitBoxInteractEvent
import net.azisaba.vanilife.npc.Npc
import net.kyori.adventure.text.Component
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.MenuType
import java.util.*

internal class TradingGoal(private val npc: Npc) : Goal<Mob> {
    private var requestedTrader: Player? = null
    private var activeTrader: Player? = null

    init {
        npc.tracker.listenHitBox(HitBoxInteractEvent::class.java) { event ->
            val player = (event.who as? BukkitPlayer)?.source() ?: return@listenHitBox
            if (!player.isSneaking) {
                requestedTrader = player
            }
        }
    }

    override fun shouldActivate(): Boolean {
        val trader = requestedTrader ?: return false
        requestedTrader = null
        if (!canStartTrade(trader)) return false
        activeTrader = trader
        return true
    }

    override fun shouldStayActive(): Boolean {
        val trader = activeTrader ?: return false
        return isTradingWith(trader)
    }

    override fun start() {
        val trader = activeTrader ?: return
        val view = MenuType.MERCHANT.builder()
            .title(npc.mob.customName() ?: Component.text("NPC"))
            .merchant(npc.merchant)
            .build(trader)
        trader.openInventory(view)
        npc.mob.pathfinder.stopPathfinding()
    }

    override fun tick() {
        val trader = activeTrader ?: return
        npc.mob.pathfinder.stopPathfinding()
        npc.mob.lookAt(trader)
    }

    override fun stop() {
        activeTrader = null
    }

    override fun getKey(): GoalKey<Mob> = NpcGoalKeys.TRADING

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK, GoalType.JUMP)

    private fun canStartTrade(player: Player): Boolean {
        if (!player.isValid || player.isDead || !player.isOnline) return false
        if (player.world != npc.mob.world) return false
        if (player.location.distanceSquared(npc.mob.location) > 64.0) return false
        return true
    }

    private fun isTradingWith(player: Player): Boolean {
        if (!player.isValid || player.isDead || !player.isOnline) return false
        if (!npc.merchant.isTrading) return false
        if (npc.merchant.trader?.uniqueId != player.uniqueId) return false
        return player.openInventory.topInventory.type == InventoryType.MERCHANT
    }
}
