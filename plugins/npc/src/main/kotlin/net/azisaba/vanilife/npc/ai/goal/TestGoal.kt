package net.azisaba.vanilife.npc.ai.goal

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import net.azisaba.vanilife.Vanilife
import org.bukkit.NamespacedKey
import org.bukkit.entity.Cow
import java.util.*

class TestGoal : Goal<Cow> {
    override fun shouldActivate(): Boolean = true

    override fun getKey(): GoalKey<Cow> = GoalKey.of(Cow::class.java, NamespacedKey(Vanilife.NAMESPACE, "test"))

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE)

    override fun tick() {
    }
}