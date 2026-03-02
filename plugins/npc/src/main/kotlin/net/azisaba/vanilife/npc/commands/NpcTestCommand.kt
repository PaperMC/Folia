package net.azisaba.vanilife.npc.commands

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.bukkit.platform.BukkitEntity
import kr.toxicity.model.api.entity.BaseEntity
import net.azisaba.vanilife.npc.Npc
import net.azisaba.vanilife.npc.ai.goal.SitGoal
import net.azisaba.vanilife.npc.ai.goal.TradingGoal
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Chicken
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe

internal object NpcTestCommand {
    fun create(): LiteralCommandNode<CommandSourceStack> = Commands.literal("npc-test")
        .executes { ctx ->
            spawnNpc(ctx)
            Command.SINGLE_SUCCESS
        }
        .build()

    private fun spawnNpc(context: CommandContext<CommandSourceStack>) {
        val plugin = Bukkit.getPluginManager().getPlugin("AzisabaNetwork.Vanilife.Npc")!!
        val location = context.source.location
        plugin.launch(plugin.regionDispatcher(location)) {
            val chicken = location.world.spawn(location, Chicken::class.java) {
                it.isSilent = true
                it.equipment.setItemInMainHand(ItemStack.of(Material.WHEAT))
            }
            val tracker = BetterModel.model("npc")
                .orElseThrow()
                .create(BaseEntity.of(BukkitEntity(chicken)))

            val merchant = Bukkit.createMerchant(Component.text("Momiji"))
            val wheatToEmerald = MerchantRecipe(ItemStack.of(Material.EMERALD), 9999).apply {
                addIngredient(ItemStack.of(Material.WHEAT, 20))
                villagerExperience = 2
            }
            val emeraldToApple = MerchantRecipe(ItemStack.of(Material.APPLE, 3), 9999).apply {
                addIngredient(ItemStack.of(Material.EMERALD, 1))
                villagerExperience = 1
            }
            merchant.setRecipes(listOf(wheatToEmerald, emeraldToApple))

            val npc = Npc(chicken, tracker, merchant)
            Bukkit.getMobGoals().addGoal(chicken, 1, TradingGoal(npc))
            Bukkit.getMobGoals().addGoal(chicken, 2, SitGoal(npc))
        }
    }
}
