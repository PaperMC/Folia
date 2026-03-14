package net.azisaba.vanilife.npc.ai

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import kr.toxicity.model.api.bukkit.platform.BukkitPlayer
import kr.toxicity.model.api.event.hitbox.HitBoxInteractEvent
import kr.toxicity.model.api.tracker.Tracker
import net.azisaba.vanilife.npc.Npc
import org.bukkit.entity.Mob
import java.util.*

internal class SitGoal(private val npc: Npc, private val mob: Mob, tracker: Tracker) : Goal<Mob> {
    private var sit: Boolean = false

    init {
        tracker.listenHitBox(HitBoxInteractEvent::class.java, ::handleHitBoxInteract)
    }

    override fun getKey(): GoalKey<Mob> = NpcGoalKeys.SIT

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.JUMP)

    override fun shouldActivate(): Boolean = sit && mob.isOnGround && !mob.isInWater

    override fun shouldStayActive(): Boolean = shouldActivate()

    override fun start() {
        mob.pathfinder.stopPathfinding()
        npc.sitDown()
    }

    override fun stop() {
        npc.standUp()
    }

    override fun tick() {
        mob.pathfinder.stopPathfinding()
    }

    private fun handleHitBoxInteract(event: HitBoxInteractEvent) {
        val player = (event.who as? BukkitPlayer)?.source() ?: return
        if (player.isSneaking) {
            sit = !sit
        }
    }
}
