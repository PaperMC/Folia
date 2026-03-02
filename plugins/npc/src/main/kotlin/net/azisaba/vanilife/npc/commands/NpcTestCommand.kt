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
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Cow
import org.bukkit.inventory.ItemStack

object NpcTestCommand {
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
            val cow = location.world.spawn(location, Cow::class.java) {
                it.isSilent = true
                it.equipment.setItemInMainHand(ItemStack.of(Material.WHEAT))
            }
            BetterModel.model("npc")
                .orElseThrow()
                .create(BaseEntity.of(BukkitEntity(cow)))
        }
    }
}
