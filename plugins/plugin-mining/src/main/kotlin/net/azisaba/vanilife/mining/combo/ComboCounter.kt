package net.azisaba.vanilife.mining.combo

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.azisaba.vanilife.mining.MiningBlockTypeTagKeys
import org.bukkit.block.Block
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ComboCounter internal constructor() {
    private val combo: AtomicInteger = AtomicInteger(0)

    private var lastMiningTime: AtomicLong = AtomicLong(0L)

    fun read(): Int {
        val now = System.currentTimeMillis()
        val isExpired = now - lastMiningTime.get() > COMBO_TIMEOUT_MILLIS
        return if (!isExpired) combo.get() else {
            combo.set(0)
            0
        }
    }

    fun increment() {
        combo.incrementAndGet()
        lastMiningTime.set(System.currentTimeMillis())
    }

    companion object {
        private const val COMBO_TIMEOUT_TICKS: Long = 20L * 10
        private const val COMBO_TIMEOUT_MILLIS: Long = COMBO_TIMEOUT_TICKS * 50L

        fun shouldIncrementCombo(block: Block): Boolean = RegistryAccess.registryAccess()
            .getRegistry(RegistryKey.BLOCK)
            .getTag(MiningBlockTypeTagKeys.ORES)
            .contains(block.type.asBlockType()!!.key())
    }
}
