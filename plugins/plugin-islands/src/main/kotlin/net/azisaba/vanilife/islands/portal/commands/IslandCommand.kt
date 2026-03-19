package net.azisaba.vanilife.islands.portal.commands

import com.github.shynixn.mccoroutine.folia.launch
import com.mojang.brigadier.Command
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.azisaba.vanilife.islands.portal.ResourceTeleporter
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal object IslandCommand : KoinComponent {
    private val plugin: Plugin by inject()
    private val teleporter: ResourceTeleporter by inject()

    fun create(): LiteralCommandNode<CommandSourceStack> =
        Commands
            .literal("island")
            .then(
                Commands
                    .literal("return")
                    .executes { context ->
                        val sender = context.source.sender
                        val player = sender as? Player
                        if (player == null) {
                            sender.sendMessage(Component.text("Only players can run this command."))
                            return@executes 0
                        }
                        plugin.launch {
                            val success = teleporter.teleportResourceToIsland(player)
                            if (!success) {
                                player.sendMessage(Component.text("Could not return to your island."))
                            }
                        }
                        Command.SINGLE_SUCCESS
                    },
            ).build()
}
