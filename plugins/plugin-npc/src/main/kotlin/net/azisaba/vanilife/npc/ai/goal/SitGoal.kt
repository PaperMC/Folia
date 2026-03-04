package net.azisaba.vanilife.npc.ai.goal

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.bukkit.platform.BukkitPlayer
import kr.toxicity.model.api.event.hitbox.HitBoxInteractEvent
import net.azisaba.vanilife.npc.Npc
import org.bukkit.entity.Mob
import java.util.*

internal class SitGoal(private val npc: Npc) : Goal<Mob> {
    private var orderedToSit: Boolean = false
    private var inSittingPose: Boolean = false

    init {
        npc.tracker.listenHitBox(HitBoxInteractEvent::class.java) { event ->
            val player = (event.who as? BukkitPlayer)?.source() ?: return@listenHitBox
            if (player.isSneaking) {
                orderedToSit = !orderedToSit
            }
        }
    }

    override fun shouldActivate(): Boolean {
        if (!orderedToSit) return false
        if (npc.mob.isInWater) return false
        if (!npc.mob.isOnGround) return false
        return true
    }

    override fun shouldStayActive(): Boolean {
        return orderedToSit && npc.mob.isOnGround && !npc.mob.isInWater
    }

    override fun tick() {
        npc.mob.pathfinder.stopPathfinding()
    }

    override fun start() {
        npc.mob.pathfinder.stopPathfinding()
        if (!inSittingPose) {
            inSittingPose = true
            npc.tracker.animate(
                "idle", AnimationModifier.builder()
                    .type(AnimationIterator.Type.LOOP)
                    .build()
            )
        }
    }

    override fun stop() {
        if (inSittingPose) {
            inSittingPose = false
            npc.tracker.stopAnimation("idle")
        }
    }

    override fun getKey(): GoalKey<Mob> = NpcGoalKeys.SIT

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.JUMP)
}
