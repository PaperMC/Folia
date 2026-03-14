package net.azisaba.vanilife.npc.trading

import org.bukkit.Bukkit
import org.bukkit.inventory.Merchant
import kotlin.random.Random

data class NpcOffers(val offers: List<NpcOffer>) {
    fun bake(maxSize: Int, random: Random = Random.Default): Merchant = Bukkit.createMerchant().apply {
        recipes = offers.shuffled(random)
            .mapNotNull { it.offer(random) }
            .take(maxSize)
    }
}
