package net.azisaba.vanilife.npc

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import kr.toxicity.model.api.bukkit.platform.BukkitEntity
import kr.toxicity.model.api.tracker.Tracker
import net.azisaba.vanilife.islands.Island
import net.azisaba.vanilife.npc.ai.goal.SitGoal
import net.azisaba.vanilife.npc.ai.goal.TradingGoal
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Nameable
import org.bukkit.RegionAccessor
import org.bukkit.entity.Chicken
import org.bukkit.entity.Mob
import org.bukkit.entity.Player

fun RegionAccessor.spawn(location: Location, npcType: NpcType): Npc {
    val chicken = spawn(location, Chicken::class.java) { spawned ->
        spawned.isSilent = true
        spawned.isPersistent = false
    }
    return WildNpcImpl(npcType, chicken)
}

sealed interface Npc : Nameable {
    val npcType: NpcType

    fun remove()

    @Deprecated("Use Npc#customName()")
    override fun getCustomName(): String? = TODO()

    @Deprecated("Use Npc#customName(net.kyori.adventure.text.Component)")
    override fun setCustomName(name: String?) = TODO()

    sealed interface Wild : Npc {
        override fun customName(): Component? = null

        override fun customName(customName: Component?) {
        }

        fun tame(player: Player)
    }

    sealed interface Tamed : Npc {
        val island: Island
    }
}

private abstract class AbstractNpcImpl(override val npcType: NpcType, protected val mob: Mob) : Npc {
    protected val tracker: Tracker = npcType.modelOrThrow().create(BukkitEntity(mob))

    init {
        Bukkit.getMobGoals().addGoal(mob, 2, TradingGoal(this, mob, tracker))
        Bukkit.getMobGoals().addGoal(mob, 1, SitGoal(mob, tracker))
    }

    override fun remove() {
        mob.remove()
        tracker.close()
    }
}

private class WildNpcImpl(npcType: NpcType, mob: Mob) : AbstractNpcImpl(npcType, mob), Npc.Wild {
    override fun tame(player: Player) {
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(
                    DialogBase.builder(Component.translatable("dialog.vanilife.tame"))
                        .inputs(
                            listOf(
                                DialogInput.text("name", Component.translatable("dialog.vanilife.name")).build()
                            )
                        ).build()
                )
                .type(
                    DialogType.notice(
                        ActionButton.builder(Component.translatable("dialog.vanilife.tame.complete")).build()
                    )
                )
        }
        player.showDialog(dialog)
    }
}
