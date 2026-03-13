package net.azisaba.vanilife.npc

import org.bukkit.entity.Player
import org.jetbrains.annotations.Range
import java.util.UUID
import java.util.concurrent.ConcurrentMap

interface NpcFriendshipMap {
    operator fun get(player: Player): @Range(from = 0, to = 1) Double

    operator fun set(player: Player, friendship: @Range(from = 0, to = 1) Double)

    class Simple(private val map: ConcurrentMap<UUID, Double>) : NpcFriendshipMap {
        override fun get(player: Player): @Range(from = 0, to = 1) Double = map[player.uniqueId] ?: 0.0

        override fun set(player: Player, friendship: @Range(from = 0, to = 1) Double) {
            require(friendship in 0.0..1.0) { "Friendship must be in range 0.0..1.0: $friendship" }
            map[player.uniqueId] = friendship
        }
    }
}
