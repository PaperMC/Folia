package net.azisaba.vanilife.islands.wrack

import com.github.shynixn.mccoroutine.folia.scope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import net.azisaba.vanilife.islands.CoastSide
import net.azisaba.vanilife.islands.IslandPos
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

interface WrackAccessor {
    fun addWrackViewer(player: Player)

    fun removeWrackViewer(player: Player)

    fun spawnWrack(wrackType: WrackType)

    suspend fun wrackTick(time: Long)
}

internal class IslandWrackAccessor(val islandPos: IslandPos, val plugin: Plugin) : WrackAccessor {
    private val viewers: MutableSet<Player> = mutableSetOf()
    private val wrackEntities: MutableList<WrackEntity> = mutableListOf()
    private val tickingWrackEntities: MutableList<WrackEntity> = mutableListOf()

    private val channel: Channel<Action> = Channel(Channel.BUFFERED)

    override fun addWrackViewer(player: Player) = enqueueAction(Action.AddViewer(player))

    override fun removeWrackViewer(player: Player) = enqueueAction(Action.RemoveViewer(player))

    override fun spawnWrack(wrackType: WrackType) = enqueueAction(Action.SpawnWrack(wrackType, CoastSide.entries.random()))

    fun enqueueAction(action: Action) {
        val result = channel.trySend(action)
        if (result.isFailure) {
            plugin.componentLogger.warn("Failed to enqueue action: $action (${result.exceptionOrNull()})")
        }
    }

    override suspend fun wrackTick(time: Long) {
        tickingWrackEntities.removeIf { !it.tick(time) }

        if (time % 200L == 0L) {
            viewers.removeIf { !it.isValid }
        }

        while (true) {
            val action = channel.tryReceive().getOrNull() ?: break
            when (action) {
                is Action.AddViewer -> addViewerAction(action)
                is Action.RemoveViewer -> removeViewerAction(action)
                is Action.SpawnWrack -> spawnWrackAction(action, time)
            }
        }
    }

    private fun addViewerAction(action: Action.AddViewer) {
        val player = action.viewer
        viewers.add(player)
        wrackEntities.forEach { it.addViewer(player) }
    }

    private fun removeViewerAction(action: Action.RemoveViewer) {
        val player = action.viewer
        viewers.remove(player)
        wrackEntities.forEach { it.removeViewer(player) }
    }

    private suspend fun spawnWrackAction(action: Action.SpawnWrack, time: Long) {
        val driftPath = DriftPath.random(islandPos, action.coastSide, Bukkit.getIslandsWorld(), plugin)
        val wrackEntity = WrackEntity(action.wrackType, Bukkit.getIslandsWorld(), driftPath, time) {
            wrackEntities.remove(it)
            tickingWrackEntities.remove(it)
        }
        viewers.forEach(wrackEntity::addViewer)
        tickingWrackEntities.add(wrackEntity)
        wrackEntities.add(wrackEntity)
    }

    sealed interface Action {
        data class AddViewer(val viewer: Player) : Action

        data class RemoveViewer(val viewer: Player) : Action

        data class SpawnWrack(val wrackType: WrackType, val coastSide: CoastSide) : Action
    }
}
