package net.azisaba.vanilife.islands.portal.commands

import com.github.shynixn.mccoroutine.folia.launch
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.azisaba.vanilife.islands.IslandPos
import net.azisaba.vanilife.islands.portal.ResourceSpawnCache
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal object ResourceCacheCommand : KoinComponent {
    private val plugin: Plugin by inject()
    private val cache: ResourceSpawnCache by inject()

    fun create(): LiteralCommandNode<CommandSourceStack> {
        val worldArg = Commands.argument("world", io.papermc.paper.command.brigadier.argument.ArgumentTypes.world())
        val seedArg = Commands.argument("seed", LongArgumentType.longArg())
        val islandXArg = Commands.argument("islandX", IntegerArgumentType.integer())
        val islandZArg = Commands.argument("islandZ", IntegerArgumentType.integer())

        return Commands.literal("resourcecache")
            .requires { source -> source.sender.hasPermission("vanilife.resourcecache.admin") || source.sender.isOp }
            .then(
                Commands.literal("show")
                    .then(worldArg.then(seedArg.then(islandXArg.then(islandZArg.executes(::show))))),
            )
            .then(
                Commands.literal("invalidate")
                    .then(worldArg.then(seedArg.executes(::invalidateSeed).then(islandXArg.then(islandZArg.executes(::invalidateOne))))),
            )
            .then(
                Commands.literal("rebuild")
                    .then(worldArg.then(seedArg.then(islandXArg.then(islandZArg.executes(::rebuildOne))))),
            )
            .build()
    }

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        val world = context.getArgument("world", org.bukkit.World::class.java)
        val seed = LongArgumentType.getLong(context, "seed")
        val islandX = IntegerArgumentType.getInteger(context, "islandX")
        val islandZ = IntegerArgumentType.getInteger(context, "islandZ")

        plugin.launch {
            val entry = cache.show(world, seed, islandX, islandZ)
            if (entry == null) {
                sender.sendMessage(Component.text("No cache entry found."))
                return@launch
            }
            sender.sendMessage(
                Component.text(
                    "${entry.worldId}|${entry.seed}|${entry.islandX}|${entry.islandZ} -> (${entry.spawnX}, ${entry.spawnY}, ${entry.spawnZ})"
                )
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun invalidateSeed(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        val world = context.getArgument("world", org.bukkit.World::class.java)
        val seed = LongArgumentType.getLong(context, "seed")
        plugin.launch {
            cache.invalidate(world, seed, null, null)
            sender.sendMessage(Component.text("Invalidated all cache entries for seed $seed."))
        }
        return Command.SINGLE_SUCCESS
    }

    private fun invalidateOne(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        val world = context.getArgument("world", org.bukkit.World::class.java)
        val seed = LongArgumentType.getLong(context, "seed")
        val islandX = IntegerArgumentType.getInteger(context, "islandX")
        val islandZ = IntegerArgumentType.getInteger(context, "islandZ")
        plugin.launch {
            cache.invalidate(world, seed, islandX, islandZ)
            sender.sendMessage(Component.text("Invalidated cache entry for ($islandX, $islandZ)."))
        }
        return Command.SINGLE_SUCCESS
    }

    private fun rebuildOne(context: CommandContext<CommandSourceStack>): Int {
        val sender: CommandSender = context.source.sender
        val world = context.getArgument("world", org.bukkit.World::class.java)
        val islandX = IntegerArgumentType.getInteger(context, "islandX")
        val islandZ = IntegerArgumentType.getInteger(context, "islandZ")
        plugin.launch {
            val islandPos = IslandPos(islandX, islandZ)
            val entry = cache.getOrCompute(islandPos, world)
            sender.sendMessage(
                Component.text("Rebuilt (${entry.islandX}, ${entry.islandZ}) -> (${entry.spawnX}, ${entry.spawnY}, ${entry.spawnZ})")
            )
        }
        return Command.SINGLE_SUCCESS
    }
}
