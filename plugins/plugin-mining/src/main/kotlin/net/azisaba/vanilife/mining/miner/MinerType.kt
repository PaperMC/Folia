package net.azisaba.vanilife.mining.miner

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import net.azisaba.vanilife.mining.MiningEnchantments
import org.bukkit.enchantments.Enchantment
import org.bukkit.Material
import org.bukkit.plugin.Plugin

@ConsistentCopyVisibility
data class MinerType private constructor(val enchantment: TypedKey<Enchantment>, private val minerFactory: (Material) -> Miner) {
    fun createMiner(sourceType: Material): Miner = minerFactory(sourceType)

    companion object {
        private val SET: MutableSet<MinerType> = mutableSetOf()
        private val BY_ENCHANTMENT: MutableMap<TypedKey<Enchantment>, MinerType> = mutableMapOf()

        val RANGE_3X3X3: MinerType = register(MinerType(MiningEnchantments.RANGE_MINING_3X3X3) { Miner.range(1) })
        val RANGE_5X5X5: MinerType = register(MinerType(MiningEnchantments.RANGE_MINING_5X5X5) { Miner.range(2) })
        val VEIN_16: MinerType = register(MinerType(MiningEnchantments.VEIN_MINING_16) { sourceType -> Miner.vein(16, sourceType) })
        val VERTICAL_DOWN_1: MinerType = register(MinerType(MiningEnchantments.VERTICAL_MINING_DOWN_1) { Miner.vertical(up = 0, down = 1) })
        val VERTICAL_UP_1: MinerType = register(MinerType(MiningEnchantments.VERTICAL_MINING_UP_1) { Miner.vertical(up = 1, down = 0) })
        val VERTICAL_UP_DOWN_1: MinerType = register(MinerType(MiningEnchantments.VERTICAL_MINING_UP_DOWN_1) { Miner.vertical(up = 1, down = 1) })

        fun byEnchantment(enchantment: Enchantment): MinerType? =
            byEnchantment(RegistryKey.ENCHANTMENT.typedKey(enchantment.key()))

        fun byEnchantment(enchantment: TypedKey<Enchantment>): MinerType? = BY_ENCHANTMENT[enchantment]

        private fun register(value: MinerType): MinerType {
            SET.add(value)
            BY_ENCHANTMENT[value.enchantment] = value
            return value
        }
    }
}
