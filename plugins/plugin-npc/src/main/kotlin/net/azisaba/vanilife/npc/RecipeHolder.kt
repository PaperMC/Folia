package net.azisaba.vanilife.npc

import net.azisaba.vanilife.npc.recipe.UnreadableRecipe
import org.bukkit.Bukkit
import org.bukkit.inventory.Merchant
import org.bukkit.inventory.MerchantRecipe

interface RecipeHolder {
    val unreadableRecipes: Set<UnreadableRecipe>

    val merchantRecipes: List<MerchantRecipe>

    fun readUnreadableRecipe(unreadableRecipe: UnreadableRecipe)

    fun rollMerchantRecipes()

    fun toMerchant(): Merchant = Bukkit.createMerchant().apply {
        recipes = unreadableRecipes.map { it.toMerchantRecipe() } + merchantRecipes
    }
}
