package net.azisaba.vanilife.cooking.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.azisaba.vanilife.item.ServerItemType
import net.kyori.adventure.key.InvalidKeyException
import net.kyori.adventure.key.Key
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture

object VGiveCommand {
    private val MUST_BE_PLAYER = SimpleCommandExceptionType(LiteralMessage("A player target is required"))
    private val UNKNOWN_ITEM = SimpleCommandExceptionType(LiteralMessage("Unknown server item"))

    fun create(): LiteralCommandNode<CommandSourceStack> = Commands.literal("v-give")
        .requires(Commands.restricted { it.sender.hasPermission("minecraft.command.give") })
        .then(
            Commands.argument("item", StringArgumentType.word())
                .suggests(::suggestItems)
                .executes { ctx -> giveSelf(ctx, 1) }
                .then(
                    Commands.argument("amount", IntegerArgumentType.integer(1))
                        .executes { ctx -> giveSelf(ctx, IntegerArgumentType.getInteger(ctx, "amount")) }
                )
        )
        .then(
            Commands.argument("targets", ArgumentTypes.players())
                .then(
                    Commands.argument("item", StringArgumentType.word())
                        .suggests(::suggestItems)
                        .executes { ctx -> giveTargets(ctx, 1) }
                        .then(
                            Commands.argument("amount", IntegerArgumentType.integer(1))
                                .executes { ctx -> giveTargets(ctx, IntegerArgumentType.getInteger(ctx, "amount")) }
                        )
                )
        )
        .build()

    private fun giveSelf(context: CommandContext<CommandSourceStack>, amount: Int): Int {
        val player = context.source.sender as? Player ?: throw MUST_BE_PLAYER.create()
        return give(listOf(player), context, amount)
    }

    private fun giveTargets(context: CommandContext<CommandSourceStack>, amount: Int): Int {
        val players = context.getArgument("targets", PlayerSelectorArgumentResolver::class.java).resolve(context.source)
        return give(players, context, amount)
    }

    private fun give(players: List<Player>, context: CommandContext<CommandSourceStack>, amount: Int): Int {
        val item = resolveItem(context.getArgument("item", String::class.java))
        val stack = ItemStack.of(item, amount)

        players.forEach { player ->
            val leftovers = player.inventory.addItem(stack.clone())
            leftovers.values.forEach { player.world.dropItem(player.location, it) }
        }

        sendFeedback(context.source.sender, players, item, amount)
        return Command.SINGLE_SUCCESS
    }

    private fun resolveItem(input: String): ServerItemType {
        val key = parseKey(input)
        return registry().get(key) ?: throw UNKNOWN_ITEM.create()
    }

    private fun parseKey(input: String): NamespacedKey = try {
        val key = if (':' in input) Key.key(input) else Key.key("vanilife", input)
        NamespacedKey.fromString(key.asString()) ?: throw UNKNOWN_ITEM.create()
    } catch (_: InvalidKeyException) {
        throw UNKNOWN_ITEM.create()
    } catch (_: IllegalArgumentException) {
        throw UNKNOWN_ITEM.create()
    }

    private fun suggestItems(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val remaining = builder.remainingLowerCase
        registry().keyStream()
            .map { it.asString() }
            .filter { it.startsWith(remaining) || it.removePrefix("vanilife:").startsWith(remaining) }
            .sorted()
            .forEach { key ->
                builder.suggest(key)
                if (key.startsWith("vanilife:")) {
                    builder.suggest(key.removePrefix("vanilife:"))
                }
            }
        return builder.buildFuture()
    }

    private fun sendFeedback(sender: CommandSender, players: List<Player>, item: ServerItemType, amount: Int) {
        val itemName = item.key.toString()
        val message = if (players.size == 1) {
            "Gave $amount of $itemName to ${players.single().name}"
        } else {
            "Gave $amount of $itemName to ${players.size} players"
        }
        sender.sendMessage(message)
    }

    private fun registry(): Registry<ServerItemType> =
        RegistryAccess.registryAccess().getRegistry(RegistryKey.SERVER_ITEM)
}
