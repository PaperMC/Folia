package net.azisaba.vanilife.npc

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.data.renderer.ModelRenderer
import net.azisaba.vanilife.cooking.CookingItems
import net.azisaba.vanilife.npc.trading.MerchantRecipeSource
import net.azisaba.vanilife.npc.trading.QuantityProvider
import net.azisaba.vanilife.npc.trading.UnquantifiedItemStack
import org.bukkit.Material

data class NpcType(val modelName: String, val merchantRecipeSource: MerchantRecipeSource) {
    fun modelOrThrow(): ModelRenderer = BetterModel.model(modelName).orElseThrow()

    companion object {
        val PLAINS: NpcType = NpcType(
            "npc",
            MerchantRecipeSource(
                MerchantRecipeSource.Offer(
                    weight = 3,
                    result = UnquantifiedItemStack.of(CookingItems.TOMATO, QuantityProvider.range(1..3)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(1..2)),
                    maxUses = 5,
                    friendshipRequired = 0.0,
                    friendshipGranted = 0.02,
                ),
                MerchantRecipeSource.Offer(
                    weight = 1,
                    result = UnquantifiedItemStack.of(CookingItems.JAPANESE_RADISH, QuantityProvider.range(1..2)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(3..6)),
                    maxUses = 5,
                    friendshipRequired = 0.1,
                    friendshipGranted = 0.02,
                ),
                MerchantRecipeSource.Offer(
                    weight = 3,
                    result = UnquantifiedItemStack.of(CookingItems.CUCUMBER, QuantityProvider.range(1..3)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(1..2)),
                    maxUses = 5,
                    friendshipRequired = 0.0,
                    friendshipGranted = 0.02,
                ),
                MerchantRecipeSource.Offer(
                    weight = 2,
                    result = UnquantifiedItemStack.of(CookingItems.ONION, QuantityProvider.range(1..2)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(2..4)),
                    maxUses = 5,
                    friendshipRequired = 0.05,
                    friendshipGranted = 0.02,
                ),
                MerchantRecipeSource.Offer(
                    weight = 2,
                    result = UnquantifiedItemStack.of(CookingItems.LETTUCE, QuantityProvider.range(1..2)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(2..4)),
                    maxUses = 5,
                    friendshipRequired = 0.05,
                    friendshipGranted = 0.02,
                ),
                MerchantRecipeSource.Offer(
                    weight = 2,
                    result = UnquantifiedItemStack.of(CookingItems.CORN, QuantityProvider.range(1..3)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(2..4)),
                    maxUses = 5,
                    friendshipRequired = 0.1,
                    friendshipGranted = 0.02,
                ),
                MerchantRecipeSource.Offer(
                    weight = 1,
                    result = UnquantifiedItemStack.of(CookingItems.STRAWBERRY, QuantityProvider.range(1..2)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(4..7)),
                    maxUses = 5,
                    friendshipRequired = 0.15,
                    friendshipGranted = 0.02,
                ),
                MerchantRecipeSource.Offer(
                    weight = 2,
                    result = UnquantifiedItemStack.of(CookingItems.BELL_PEPPER, QuantityProvider.range(1..2)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(2..4)),
                    maxUses = 5,
                    friendshipRequired = 0.05,
                    friendshipGranted = 0.02,
                ),
                MerchantRecipeSource.Offer(
                    weight = 2,
                    result = UnquantifiedItemStack.of(CookingItems.EGGPLANT, QuantityProvider.range(1..2)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(2..4)),
                    maxUses = 5,
                    friendshipRequired = 0.05,
                    friendshipGranted = 0.02,
                ),
                MerchantRecipeSource.Offer(
                    weight = 1,
                    result = UnquantifiedItemStack.of(CookingItems.GRAPE, QuantityProvider.range(1..2)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(4..7)),
                    maxUses = 5,
                    friendshipRequired = 0.15,
                    friendshipGranted = 0.02,
                ),
                MerchantRecipeSource.Offer(
                    weight = 1,
                    result = UnquantifiedItemStack.of(CookingItems.PEACH, QuantityProvider.range(1..2)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(5..8)),
                    maxUses = 5,
                    friendshipRequired = 0.2,
                    friendshipGranted = 0.02,
                ),
                MerchantRecipeSource.Offer(
                    weight = 1,
                    result = UnquantifiedItemStack.of(CookingItems.BLUEBERRY, QuantityProvider.range(1..2)),
                    cost = UnquantifiedItemStack.of(Material.APPLE, QuantityProvider.range(4..7)),
                    maxUses = 5,
                    friendshipRequired = 0.15,
                    friendshipGranted = 0.02,
                ),
            )
        )
    }
}
