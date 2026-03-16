package net.azisaba.vanilife.mining.combo

import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

open class ComboCounterManager {
    private val counterByPlayer: MutableMap<Int, ComboCounter> = ConcurrentHashMap()

    fun getOrCreate(player: Player): ComboCounter = counterByPlayer.computeIfAbsent(player.entityId) { ComboCounter() }

    fun remove(player: Player) {
        counterByPlayer.remove(player.entityId)
    }

    companion object Default : ComboCounterManager()
}
