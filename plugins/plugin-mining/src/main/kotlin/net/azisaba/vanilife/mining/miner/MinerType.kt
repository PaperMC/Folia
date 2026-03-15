package net.azisaba.vanilife.mining.miner

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import net.azisaba.vanilife.mining.MiningEnchantments
import org.bukkit.enchantments.Enchantment
import org.bukkit.plugin.Plugin

@ConsistentCopyVisibility
data class MinerType private constructor(val enchantment: TypedKey<Enchantment>, val miner: Miner) : Miner {
    override suspend fun perform(context: Miner.Context, plugin: Plugin) {
        miner.perform(context, plugin)
    }

    companion object {
        private val SET: MutableSet<MinerType> = mutableSetOf()
        private val BY_ENCHANTMENT: MutableMap<TypedKey<Enchantment>, MinerType> = mutableMapOf()

        val RANGE_3X3X3: MinerType = register(MinerType(MiningEnchantments.RANGE_MINING_3X3X3, Miner.range(1)))
        val RANGE_5X5X5: MinerType = register(MinerType(MiningEnchantments.RANGE_MINING_5X5X5, Miner.range(2)))

        fun byEnchantment(enchantment: Enchantment): MinerType? =
            byEnchantment(RegistryKey.ENCHANTMENT.typedKey(enchantment.key()))

        fun byEnchantment(enchantment: TypedKey<Enchantment>): MinerType? = BY_ENCHANTMENT[enchantment]

        private fun register(type: MinerType): MinerType {
            SET.add(type)
            BY_ENCHANTMENT[type.enchantment] = type
            return type
        }
    }
}
