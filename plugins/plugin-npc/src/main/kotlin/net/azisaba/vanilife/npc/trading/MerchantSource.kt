package net.azisaba.vanilife.npc.trading

import net.azisaba.vanilife.Season
import org.bukkit.inventory.MerchantRecipe

class MerchantSource {
    data class Offer(val baseWeight: Int, val merchantRecipe: MerchantRecipe) {
        fun weight(season: Season.Sub): Int {
            merchantRecipe.result.type
            return baseWeight
        }
    }
}
