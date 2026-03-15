package net.azisaba.vanilife.npc.ai

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import kr.toxicity.model.api.bukkit.platform.BukkitPlayer
import kr.toxicity.model.api.event.hitbox.HitBoxInteractEvent
import kr.toxicity.model.api.tracker.Tracker
import net.azisaba.vanilife.npc.Npc
import net.azisaba.vanilife.npc.NpcFonts
import net.azisaba.vanilife.npc.UnreadableRecipe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.MenuType
import java.util.*

internal class TradingGoal(
    private val npc: Npc,
    private val mob: Mob,
    tracker: Tracker,
) : Goal<Mob> {
    private var requestedTrader: Player? = null
    private var activeTrader: Player? = null

    init {
        tracker.listenHitBox(HitBoxInteractEvent::class.java, ::handleHitBoxInteract)
    }

    override fun getKey(): GoalKey<Mob> = NpcGoalKeys.TRADING

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK, GoalType.JUMP)

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
            .title(
                Component.text()
                    .append(Component.text(npc.npcType.icon, NamedTextColor.WHITE).font(NpcFonts.NPC_ICONS))
                    .appendSpace()
                    .append(Component.text("ねこ"))
                    .build()
            )
            .merchant(npc.merchant)
            .build(trader)
        trader.openInventory(view)
        mob.pathfinder.stopPathfinding()
    }

    override fun stop() {
        activeTrader = null
    }

    override fun tick() {
        val trader = activeTrader ?: return
        mob.pathfinder.stopPathfinding()
        mob.lookAt(trader)
    }

    private fun handleHitBoxInteract(event: HitBoxInteractEvent) {
        val player = (event.who as? BukkitPlayer)?.source() ?: return
        val itemStack = player.equipment.itemInMainHand
        val serverItem = itemStack.serverItem()
        if (!player.isSneaking && (serverItem == null || UnreadableRecipe.byItem(serverItem) == null)) {
            requestedTrader = player
        }
    }

    private fun canStartTrade(player: Player): Boolean {
        if (!player.isValid || player.isDead || !player.isOnline) return false
        if (player.world != mob.world) return false
        if (player.location.distanceSquared(mob.location) > 64.0) return false
        return true
    }

    private fun isTradingWith(player: Player): Boolean {
        if (!player.isValid || player.isDead || !player.isOnline) return false
        /* if (!npc.merchant.isTrading) return false
        if (npc.merchant.trader?.uniqueId != player.uniqueId) return false */
        return player.openInventory.topInventory.type == InventoryType.MERCHANT
    }
}
