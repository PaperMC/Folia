package net.azisaba.vanilife.npc

import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.bukkit.platform.BukkitEntity
import kr.toxicity.model.api.tracker.Tracker
import net.azisaba.vanilife.npc.ai.ReadRecipeGoal
import net.azisaba.vanilife.npc.ai.SitGoal
import net.azisaba.vanilife.npc.ai.TradingGoal
import net.azisaba.vanilife.npc.trading.NpcTradeAccess
import net.azisaba.vanilife.npc.trading.OnMemoryNpcTradeAccess
import net.kyori.adventure.audience.Audience
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.RegionAccessor
import org.bukkit.entity.Chicken
import org.bukkit.entity.Mob

fun RegionAccessor.spawn(location: Location, npcType: NpcType): Npc {
    val chicken = spawn(location, Chicken::class.java) { spawned ->
        spawned.isSilent = true
        spawned.isPersistent = false
    }
    return Npc(npcType, chicken)
}

class Npc internal constructor(
    val npcType: NpcType,
    val mob: Mob,
    val tradeAccess: NpcTradeAccess = OnMemoryNpcTradeAccess(npcType),
) : Audience, NpcTradeAccess by tradeAccess {
    val isSitting: Boolean
        get() = tracker.bones().any { bone -> bone.runningAnimation()?.name == "sit" }

    private val tracker: Tracker = npcType.modelOrThrow().create(BukkitEntity(mob))

    init {
        Bukkit.getMobGoals().addGoal(mob, 1, ReadRecipeGoal(this, mob, tracker))
        Bukkit.getMobGoals().addGoal(mob, 3, TradingGoal(this, mob, tracker))
        Bukkit.getMobGoals().addGoal(mob, 2, SitGoal(this, mob, tracker))
        rollMerchantRecipes()
    }

    fun sitDown() {
        tracker.animate(
            "sit",
            AnimationModifier.builder()
                .type(AnimationIterator.Type.LOOP)
                .build()
        )
    }

    fun standUp() {
        tracker.stopAnimation("sit")
    }

    fun remove() {
        mob.remove()
        tracker.close()
    }
}
