package net.azisaba.vanilife.npc.trading

import net.azisaba.vanilife.Season
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Merchant
import org.bukkit.inventory.MerchantRecipe
import kotlin.math.abs
import kotlin.random.Random

fun MerchantConstructor(builderAction: MerchantConstructor.Builder.() -> Unit): MerchantConstructor {
    val builder = MerchantConstructor.Builder()
    builder.builderAction()
    return builder.build()
}

class MerchantConstructor(
    private val maxSize: Int,
    private val weightedMerchantRecipes: Collection<WeightedMerchantRecipe>
) {
    fun createMerchant(): Merchant = Bukkit.createMerchant().apply {
        recipes = sampleMerchantRecipes(Season.Sub.now(), Random.Default)
    }

    private fun sampleMerchantRecipes(season: Season.Sub, random: Random): List<MerchantRecipe> {
        if (weightedMerchantRecipes.isEmpty()) return emptyList()

        val pool = weightedMerchantRecipes.associateWith { it.weight(season) }.toMutableMap()
        val result = mutableListOf<MerchantRecipe>()

        repeat(minOf(maxSize, pool.size)) {
            val totalWeight = pool.values.sum()

            if (totalWeight <= 0) return@repeat

            val r = random.nextInt(totalWeight)

            var acc = 0
            val selected = pool.entries.first {
                acc += it.value
                r < acc
            }.key

            result.add(selected.merchantRecipe)
            pool.remove(selected)
        }

        return result.toList()
    }

    data class WeightedMerchantRecipe(val baseWeight: Int, val merchantRecipe: MerchantRecipe) {
        fun weight(season: Season.Sub): Int {
            val relatedSeasons = buildSet {
                merchantRecipe.result.serverItem()?.let {
                    addAll(it.peakSeason())
                }
                merchantRecipe.ingredients.mapNotNull { it.serverItem() }.forEach {
                    addAll(it.peakSeason())
                }
            }

            if (relatedSeasons.isEmpty()) return baseWeight

            val sorted = relatedSeasons.sorted()
            val ranges = buildRanges(sorted)

            val seasonIndex = season.toIndex()

            val bonus = ranges.maxOf { range ->
                val center = (range.start.toIndex() + range.end.toIndex()) / 2
                val radius = (range.end.toIndex() - range.start.toIndex()) / 2 + 1

                val distance = abs(seasonIndex - center)

                (radius - distance).coerceAtLeast(0)
            }

            return baseWeight + bonus * SEASONAL_BONUS_MULTIPLIER
        }

        private fun buildRanges(sorted: List<Season.Sub>): List<Range> {
            val ranges = mutableListOf<Range>()

            var start = sorted.first()
            var prev = start

            for (i in 1 until sorted.size) {
                val current = sorted[i]

                if (prev.next() != current) {
                    ranges.add(Range(start, prev))
                    start = current
                }

                prev = current
            }

            ranges.add(Range(start, prev))
            return ranges
        }

        private companion object {
            const val SEASONAL_BONUS_MULTIPLIER: Int = 4
        }

        private data class Range(val start: Season.Sub, val end: Season.Sub)
    }

    class Builder internal constructor() {
        var maxSize: Int = 15

        private val weightedMerchantRecipes: MutableList<WeightedMerchantRecipe> = mutableListOf()

        fun add(baseWeight: Int, result: ItemStack, ingredient: ItemStack, maxUses: Int) {
            weightedMerchantRecipes.add(
                WeightedMerchantRecipe(
                    baseWeight,
                    MerchantRecipe(result, maxUses).apply { addIngredient(ingredient) }
                )
            )
        }

        fun add(baseWeight: Int, result: ItemStack, ingredientA: ItemStack, ingredientB: ItemStack, maxUses: Int) {
            weightedMerchantRecipes.add(
                WeightedMerchantRecipe(
                    baseWeight,
                    MerchantRecipe(result, maxUses).apply { ingredients = listOf(ingredientA, ingredientB) }
                )
            )
        }

        internal fun build(): MerchantConstructor = MerchantConstructor(maxSize, weightedMerchantRecipes.toList())
    }
}
