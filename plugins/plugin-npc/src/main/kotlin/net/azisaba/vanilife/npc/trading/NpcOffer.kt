package net.azisaba.vanilife.npc.trading

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import net.azisaba.vanilife.Season
import net.azisaba.vanilife.item.ServerItem
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import kotlin.random.Random

fun interface NpcOffer {
    fun offer(random: Random): MerchantRecipe?

    companion object Builtins {
        @JvmName("materialToMaterial")
        fun item(
            result: Pair<Material, IntRange>,
            cost: Pair<Material, IntRange>,
            maxUses: Int = 8,
        ): NpcOffer = NpcOffer { random ->
            MerchantRecipe(ItemStack.of(result.first, result.second.random(random)), maxUses).apply {
                addIngredient(ItemStack.of(cost.first, cost.second.random(random)))
            }
        }

        @JvmName("serverItemToMaterial")
        fun item(
            result: Pair<TypedKey<ServerItem>, IntRange>,
            cost: Pair<Material, IntRange>,
            maxUses: Int = 8,
        ): NpcOffer = NpcOffer { random ->
            MerchantRecipe(ItemStack.of(result.first, result.second.random(random)), maxUses).apply {
                addIngredient(ItemStack.of(cost.first, cost.second.random(random)))
            }
        }

        @JvmName("materialToServerItem")
        fun item(
            result: Pair<Material, IntRange>,
            cost: Pair<TypedKey<ServerItem>, IntRange>,
            maxUses: Int = 8,
        ): NpcOffer = NpcOffer { random ->
            MerchantRecipe(ItemStack.of(result.first, result.second.random(random)), maxUses).apply {
                addIngredient(ItemStack.of(cost.first, cost.second.random(random)))
            }
        }

        @JvmName("serverItemToMaterialWithSeasonalDiscount")
        fun itemWithSeasonalDiscount(
            result: Pair<TypedKey<ServerItem>, IntRange>,
            cost: Pair<Material, IntRange>,
            maxUses: Int = 8,
        ): NpcOffer = NpcOffer { random ->
            MerchantRecipe(ItemStack.of(result.first, result.second.random(random)), maxUses).apply {
                val costAmount = cost.second.random(random)
                addIngredient(ItemStack.of(cost.first, costAmount))

                val peakSeason = RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.SERVER_ITEM)
                    .getOrThrow(result.first)
                    .peakSeason()

                if (Season.Sub.now() in peakSeason) {
                    specialPrice = -(costAmount * 0.3).toInt()
                }
            }
        }

        fun randomChoice(vararg offers: NpcOffer): NpcOffer = NpcOffer { random ->
            if (offers.isNotEmpty()) offers.random(random).offer(random) else null
        }

        fun weightRandom(map: Map<NpcOffer, Int>): NpcOffer = NpcOffer { random ->
            if (map.isEmpty()) return@NpcOffer null

            val totalWeight = map.values.sum()
            if (totalWeight <= 0) return@NpcOffer null

            val r = random.nextInt(totalWeight)

            var acc = 0
            for ((offer, weight) in map) {
                acc += weight
                if (r < acc) {
                    return@NpcOffer offer.offer(random)
                }
            }

            null
        }

        fun withProbability(offer: NpcOffer, chance: Double): NpcOffer = NpcOffer { random ->
            if (random.nextDouble() <= chance) offer.offer(random) else null
        }

        fun withSeasons(offer: NpcOffer, vararg seasons: Season.Sub) = NpcOffer { random ->
            if (Season.Sub.now() in seasons) offer.offer(random) else null
        }
    }
}
