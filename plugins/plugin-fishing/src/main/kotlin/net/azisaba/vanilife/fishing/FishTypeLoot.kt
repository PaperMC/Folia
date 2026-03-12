package net.azisaba.vanilife.fishing

import net.azisaba.vanilife.Season
import kotlin.random.Random

fun FishType.Companion.loot(context: FishingContext, random: Random = Random.Default): FishType {
    val weightedTypes = allTypes()
        .map { fishType -> fishType to computeWeight(fishType, context) }
        .filter { (_, weight) -> weight > 0f }

    if (weightedTypes.isEmpty()) {
        return allTypes().random(random)
    }

    val totalWeight = weightedTypes.sumOf { it.second.toDouble() }.toFloat()
    var remaining = random.nextFloat() * totalWeight
    weightedTypes.forEach { (fishType, weight) ->
        remaining -= weight
        if (remaining <= 0f) {
            return fishType
        }
    }

    return weightedTypes.last().first
}

private fun computeWeight(fishType: FishType, context: FishingContext): Float =
    computeSeasonWeight(fishType, context.season) * computeRarityWeight(fishType)

private fun computeRarityWeight(fishType: FishType): Float =
    (1.05f - fishType.rarity).coerceAtLeast(0.05f)

private fun computeSeasonWeight(fishType: FishType, season: Season.Sub): Float {
    val currentIndex = season.toIndex()
    val distance = fishType.peakSeason
        .minOf { peakSeason ->
            val peakIndex = peakSeason.toIndex()
            val absoluteDistance = kotlin.math.abs(currentIndex - peakIndex)
            minOf(absoluteDistance, 12 - absoluteDistance).toFloat()
        }

    return (1f - distance / 6f).coerceAtLeast(0.1f)
}

private fun Season.Sub.toIndex(): Int = season.ordinal * 3 + stage.ordinal
