package net.azisaba.vanilife.npc.trading

import kotlin.random.Random

fun interface QuantityProvider {
    fun provide(random: Random): Int

    companion object Builtins {
        fun fixed(value: Int): QuantityProvider = QuantityProvider { _ -> value }

        fun range(range: IntRange): QuantityProvider = QuantityProvider { random -> range.random(random) }

        fun bonus(base: Int, bonus: IntRange): QuantityProvider = QuantityProvider { random -> base + bonus.random(random) }
    }
}
