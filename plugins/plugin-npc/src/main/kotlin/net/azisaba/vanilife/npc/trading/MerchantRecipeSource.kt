package net.azisaba.vanilife.npc.trading

import org.bukkit.Bukkit
import org.bukkit.inventory.Merchant
import org.bukkit.inventory.MerchantRecipe
import kotlin.random.Random

class MerchantRecipeSource(private val offers: Collection<Offer>) {
    constructor(vararg offers: Offer) : this(offers.toList())

    val totalWeight: Int = offers.sumOf(Offer::weight)

    fun roll(maxCount: Int, random: Random = Random.Default): List<MerchantRecipe> {
        if (offers.isEmpty()) return emptyList()

        val pool = offers.toMutableList()
        val result = mutableListOf<MerchantRecipe>()

        repeat(minOf(maxCount, pool.size)) {
            val remainingWeight = pool.sumOf(Offer::weight)
            val r = random.nextInt(remainingWeight)

            var acc = 0
            for ((index, offer) in pool.withIndex()) {
                acc += offer.weight
                if (r < acc) {
                    result.add(offer.toMerchantRecipe(random))
                    pool.removeAt(index)
                    break
                }
            }
        }

        return result.toList()
    }

    fun createMerchant(): Merchant {
        val merchant = Bukkit.createMerchant()
        merchant.recipes = roll(25)
        return merchant
    }

    data class Offer(
        val weight: Int,
        val result: UnquantifiedItemStack,
        val cost: UnquantifiedItemStack,
        val optionalCost: UnquantifiedItemStack? = null,
        val maxUses: Int,
        val friendshipRequired: Double,
        val friendshipGranted: Double,
    ) {
        fun toMerchantRecipe(random: Random): MerchantRecipe {
            val merchantRecipe = MerchantRecipe(result.resolve(random), maxUses)
            merchantRecipe.addIngredient(cost.resolve(random))
            if (optionalCost != null) {
                merchantRecipe.addIngredient(optionalCost.resolve(random))
            }
            return merchantRecipe
        }
    }
}
