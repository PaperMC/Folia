package net.azisaba.vanilife.cooking.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

object TomatoCommand {
    fun create(): LiteralCommandNode<CommandSourceStack> = Commands.literal("tomato")
        .executes { ctx ->
            val world = ctx.source.location.world
            val x = ctx.source.location.blockX()
            val z = ctx.source.location.blockZ()
            ctx.source.sender.sendMessage(Component.text()
                .append(Component.text("RESOURCE_OVERWORLD_WORLD_SURFACE: ${world.getHighestBlockYAt(x, z, HeightMap.RESOURCE_OVERWORLD_WORLD_SURFACE)}", NamedTextColor.GREEN))
                .appendNewline()
                .append(Component.text("RESOURCE_NETHER_WORLD_SURFACE: ${world.getHighestBlockYAt(x, z, HeightMap.RESOURCE_NETHER_WORLD_SURFACE)}", NamedTextColor.RED))
                .appendNewline()
                .append(Component.text("RESOURCE_END_WORLD_SURFACE: ${world.getHighestBlockYAt(x, z, HeightMap.RESOURCE_END_WORLD_SURFACE)}", NamedTextColor.YELLOW))
            )
            Command.SINGLE_SUCCESS
        }
        .build()
}