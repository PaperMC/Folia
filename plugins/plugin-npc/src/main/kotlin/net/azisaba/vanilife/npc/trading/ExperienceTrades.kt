package net.azisaba.vanilife.npc.trading

import net.azisaba.vanilife.npc.NpcItems
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantInventory
import org.bukkit.inventory.MerchantRecipe

fun MerchantRecipe.experienceCost(): Int = ingredients
    .filter { it.isOf(NpcItems.EXPERIENCE) }
    .sumOf(ItemStack::getAmount)

internal fun MerchantInventory.updateExperiencePreview(trader: Player, merchantRecipe: MerchantRecipe) {
    val required = merchantRecipe.experienceCost()
    if (required > 0) {
        val amount = if (!trader.gameMode.isInvulnerable) minOf(required, trader.level) else required
        val slotIndex = merchantRecipe.ingredients.indexOfLast { it.isOf(NpcItems.EXPERIENCE) }
        setItem(slotIndex, ItemStack.of(NpcItems.EXPERIENCE, amount))
        clearExperiences { index, _ -> index == slotIndex }
    } else {
        clearExperiences()
    }
}

internal fun MerchantInventory.clearExperiences(predicate: (Int, ItemStack) -> Boolean = { _, _ -> true }) {
    forEachIndexed { index, itemStack ->
        if (itemStack?.isOf(NpcItems.EXPERIENCE) == true && predicate(index, itemStack)) {
            clear(index)
        }
    }
}
