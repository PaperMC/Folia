package net.azisaba.vanilife.npc.ai

import com.destroystokyo.paper.entity.ai.GoalKey
import net.azisaba.vanilife.Vanilife
import org.bukkit.NamespacedKey
import org.bukkit.entity.Mob
import org.koin.core.component.KoinComponent

object NpcGoalKeys : KoinComponent {
    val READ_RECIPE: GoalKey<Mob> = GoalKey.of(Mob::class.java, NamespacedKey(Vanilife.NAMESPACE, "npc/read_recipe"))
    val RESEARCHING: GoalKey<Mob> = GoalKey.of(Mob::class.java, NamespacedKey(Vanilife.NAMESPACE, "npc/researching"))
    val SIT: GoalKey<Mob> = GoalKey.of(Mob::class.java, NamespacedKey(Vanilife.NAMESPACE, "npc/sit"))
    val TRADING: GoalKey<Mob> = GoalKey.of(Mob::class.java, NamespacedKey(Vanilife.NAMESPACE, "npc/trading"))
}
