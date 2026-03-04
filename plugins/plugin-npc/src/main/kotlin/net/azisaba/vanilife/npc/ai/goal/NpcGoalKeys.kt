package net.azisaba.vanilife.npc.ai.goal

import com.destroystokyo.paper.entity.ai.GoalKey
import org.bukkit.NamespacedKey
import org.bukkit.entity.Mob
import org.bukkit.plugin.Plugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object NpcGoalKeys : KoinComponent {
    private val plugin: Plugin by inject()

    val SIT: GoalKey<Mob> = GoalKey.of(Mob::class.java, NamespacedKey(plugin, "sit"))
    val TRADING: GoalKey<Mob> = GoalKey.of(Mob::class.java, NamespacedKey(plugin, "trading"))
}
