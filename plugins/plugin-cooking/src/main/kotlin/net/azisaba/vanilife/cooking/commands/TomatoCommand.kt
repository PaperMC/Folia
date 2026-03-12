package net.azisaba.vanilife.cooking.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Consumable
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import net.azisaba.vanilife.cooking.CookingItems
import net.kyori.adventure.key.Key
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

object TomatoCommand {
    fun create(): LiteralCommandNode<CommandSourceStack> = Commands.literal("tomato")
        .executes { ctx ->
            val inventory = (ctx.source.sender as Player).inventory
            val itemStack = ItemStack.of(Material.STICK)
            itemStack.setData(DataComponentTypes.ITEM_MODEL, Key.key("fishing_rod"))
            itemStack.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable().animation(ItemUseAnimation.NONE).build())
            inventory.addItem(itemStack)
            Command.SINGLE_SUCCESS
        }
        .build()
}