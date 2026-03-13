package net.azisaba.vanilife.npc.ai

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.bukkit.platform.BukkitPlayer
import kr.toxicity.model.api.event.hitbox.HitBoxInteractEvent
import kr.toxicity.model.api.tracker.Tracker
import org.bukkit.entity.Mob
import java.util.*

internal class SitGoal(private val mob: Mob, private val tracker: Tracker) : Goal<Mob> {
    private var orderedToSit: Boolean = false
    private var inSittingPose: Boolean = false

    init {
        tracker.listenHitBox(HitBoxInteractEvent::class.java) { event ->
            val player = (event.who as? BukkitPlayer)?.source() ?: return@listenHitBox
            if (player.isSneaking) {
                orderedToSit = !orderedToSit
            }
        }
    }

    override fun getKey(): GoalKey<Mob> = NpcGoalKeys.SIT

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.JUMP)

    override fun shouldActivate(): Boolean {
        if (!orderedToSit) return false
        if (mob.isInWater) return false
        if (!mob.isOnGround) return false
        return true
    }

    override fun shouldStayActive(): Boolean {
        return orderedToSit && mob.isOnGround && !mob.isInWater
    }

    override fun start() {
        mob.pathfinder.stopPathfinding()
        if (!inSittingPose) {
            inSittingPose = true
            tracker.animate(
                "idle",
                AnimationModifier.builder()
                    .type(AnimationIterator.Type.LOOP)
                    .build()
            )
        }
    }

    override fun stop() {
        if (inSittingPose) {
            inSittingPose = false
            tracker.stopAnimation("idle")
        }
    }

    override fun tick() {
        mob.pathfinder.stopPathfinding()
    }
}
