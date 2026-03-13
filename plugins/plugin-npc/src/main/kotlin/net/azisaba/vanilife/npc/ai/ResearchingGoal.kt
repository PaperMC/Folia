package net.azisaba.vanilife.npc.ai

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import kr.toxicity.model.api.bukkit.platform.BukkitPlayer
import kr.toxicity.model.api.event.hitbox.HitBoxInteractEvent
import kr.toxicity.model.api.tracker.Tracker
import net.azisaba.vanilife.npc.NpcItems
import org.bukkit.Particle
import org.bukkit.entity.Mob
import org.bukkit.inventory.ItemStack
import java.util.EnumSet

internal class ResearchingGoal(private val mob: Mob, tracker: Tracker) : Goal<Mob> {
    private var researchRequest: ItemStack? = null

    private var researchingRecipe: ItemStack? = null

    init {
        tracker.listenHitBox(HitBoxInteractEvent::class.java) { event ->
            val player = (event.who as? BukkitPlayer)?.source() ?: return@listenHitBox
            val itemInMainHand = player.equipment.itemInMainHand
            if (!player.isSneaking && itemInMainHand.isOf(NpcItems.UNREADABLE_RECIPE)) {
                researchRequest = itemInMainHand
            }
        }
    }

    override fun getKey(): GoalKey<Mob> = NpcGoalKeys.RESEARCHING

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK, GoalType.JUMP)

    override fun shouldActivate(): Boolean {
        val request = researchRequest ?: return false
        researchRequest = null
        researchingRecipe = request
        return true
    }

    override fun shouldStayActive(): Boolean = researchingRecipe != null

    override fun stop() {
        researchingRecipe = null
    }

    override fun tick() {
        mob.world.spawnParticle(Particle.ENCHANT, mob.location, (2..5).random())
    }
}