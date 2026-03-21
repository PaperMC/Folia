package net.azisaba.vanilife.islands

import com.github.shynixn.mccoroutine.folia.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.islands.repository.PrimaryIslandData
import net.azisaba.vanilife.islands.waves.IslandWaveAccessor
import net.azisaba.vanilife.islands.waves.WaveAccessor
import net.azisaba.vanilife.islands.wrack.IslandWrackAccessor
import net.azisaba.vanilife.islands.wrack.WrackAccessor
import net.azisaba.vanilife.world.IslandPos
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.audience.ForwardingAudience
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class)
class Island internal constructor(
    override val pos: IslandPos,
    val world: World,
    override val ownerUuid: UUID,
    override val primaryData: PrimaryIslandData.Writable,
    private val plugin: Plugin,
) : IslandInfo, ForwardingAudience,
    WaveAccessor by IslandWaveAccessor(pos),
    WrackAccessor by IslandWrackAccessor(pos, world, plugin) {
    private val players: MutableSet<Player> = mutableSetOf()

    private var job: Job? = null
    private val channel: Channel<Action> = Channel(Channel.BUFFERED)

    fun addPlayer(player: Player, withTeleport: Boolean = true) = enqueueAction(
        Action.AddPlayer(player, withTeleport)
    )

    fun removePlayer(player: Player) = enqueueAction(
        Action.RemovePlayer(player)
    )

    fun enqueueAction(action: Action) {
        ensureTicking()
        val result = channel.trySend(action)
        if (result.isFailure) {
            plugin.componentLogger.warn("Failed to enqueue action: $action (${result.exceptionOrNull()})")
        }
    }

    override fun audiences(): Iterable<Audience> = players.toSet()

    private suspend fun tick(time: Long) {
        waveTick(time)
        wrackTick(time)

        while (true) {
            val action = channel.tryReceive().getOrNull() ?: break
            when (action) {
                is Action.AddPlayer -> addPlayerAction(action)
                is Action.RemovePlayer -> removePlayerAction(action)
            }
        }

        if (players.isEmpty() && channel.isEmpty) {
            stopTicking()
        }
    }

    private suspend fun addPlayerAction(action: Action.AddPlayer) {
        val player = action.player

        if (action.withTeleport && !player.teleportAsync(primaryData.spawnPoint(pos, world)).await()) {
            return
        }

        if (players.add(player)) {
            addWaveViewer(player.uniqueId)
            addWrackViewer(player)
        }
    }

    private fun removePlayerAction(action: Action.RemovePlayer) {
        val player = action.player
        if (players.remove(player)) {
            removeWaveViewer(player.uniqueId)
            removeWrackViewer(player)
        }
    }

    private fun ensureTicking() {
        if (job?.isActive == true) return

        job = plugin.launch {
            var time = 0L
            while (isActive) {
                tick(time++)
                delay(50L)
            }
        }
    }

    private fun stopTicking() {
        job?.cancel()
        job = null
    }

    sealed interface Action {
        data class AddPlayer(val player: Player, val withTeleport: Boolean) : Action

        data class RemovePlayer(val player: Player) : Action
    }
}
