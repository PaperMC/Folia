package net.azisaba.vanilife.npc.trading

import org.bukkit.inventory.MerchantRecipe
import kotlin.random.Random

// TODO: Make it configurable from TOML or YAML
data class NpcOffers(val offers: List<NpcOffer>) {
    fun roll(maxSize: Int = DEFAULT_MAX_SIZE, random: Random = Random.Default): List<MerchantRecipe> = offers.shuffled(random)
        .mapNotNull { it.offer(random) }
        .take(maxSize)

    fun withNewOffer(newOffer: NpcOffer): NpcOffers = NpcOffers(listOf(newOffer) + offers)

    companion object {
        const val DEFAULT_MAX_SIZE: Int = 12
    }
}
