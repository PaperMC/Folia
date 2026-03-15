package net.azisaba.vanilife.npc.trading

import net.azisaba.vanilife.npc.NpcType
import net.azisaba.vanilife.npc.UnreadableRecipe
import org.bukkit.Bukkit
import org.bukkit.inventory.Merchant
import kotlin.random.Random

interface NpcTradeAccess {
    val offers: NpcOffers

    val merchant: Merchant

    val readRecipes: Set<UnreadableRecipe>

    fun rollMerchantRecipes()

    fun readRecipe(recipe: UnreadableRecipe)
}

internal open class OnMemoryNpcTradeAccess(npcType: NpcType) : NpcTradeAccess {
    final override var offers: NpcOffers = npcType.offers
        protected set

    final override val merchant: Merchant = Bukkit.createMerchant()

    override val readRecipes: Set<UnreadableRecipe>
        get() = readRecipesMutable.toSet()

    protected val readRecipesMutable: MutableSet<UnreadableRecipe> = mutableSetOf()

    override fun rollMerchantRecipes() {
        merchant.recipes = offers.roll()
    }

    override fun readRecipe(recipe: UnreadableRecipe) {
        readRecipesMutable.add(recipe)
        val offer = NpcOffer.recipe(recipe)
        offers = offers.withNewOffer(offer)
        merchant.recipes = listOf(offer.offer(Random.Default)) + merchant.recipes
    }
}
