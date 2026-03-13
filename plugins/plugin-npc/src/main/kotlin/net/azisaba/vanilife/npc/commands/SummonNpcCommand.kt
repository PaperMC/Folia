package net.azisaba.vanilife.npc.commands

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.azisaba.vanilife.npc.NpcType
import net.azisaba.vanilife.npc.spawn
import org.bukkit.plugin.Plugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal object SummonNpcCommand : KoinComponent {
    private val plugin: Plugin by inject()

    fun create(): LiteralCommandNode<CommandSourceStack> = Commands.literal("summon-npc")
        .executes(::summon)
        .build()

    private fun summon(context: CommandContext<CommandSourceStack>): Int {
        val spawnLocation = context.source.location
        plugin.launch(plugin.regionDispatcher(spawnLocation)) {
            spawnLocation.world.spawn(spawnLocation, NpcType.NEKO)
        }
        return Command.SINGLE_SUCCESS
    }
}
