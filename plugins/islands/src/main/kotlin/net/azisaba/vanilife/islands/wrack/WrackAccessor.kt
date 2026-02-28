package net.azisaba.vanilife.islands.wrack

import com.github.shynixn.mccoroutine.folia.scope
import kotlinx.coroutines.launch
import net.azisaba.vanilife.islands.CoastSide
import net.azisaba.vanilife.islands.IslandPos
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

interface WrackAccessor {
    fun addWrackViewer(player: Player)

    fun removeWrackViewer(player: Player)

    fun enqueueSpawnWrack(wrackType: WrackType)

    suspend fun wrackTick(time: Long)
}

internal class IslandWrackAccessor(val islandPos: IslandPos, val plugin: Plugin) : WrackAccessor {
    private val scope = plugin.scope

    private val viewers: MutableSet<Player> = mutableSetOf()

    private val spawnQueue: ArrayDeque<WrackType> = ArrayDeque()

    private val wrackEntities: MutableList<WrackEntity> = mutableListOf()
    private val tickingWrackEntities: MutableList<WrackEntity> = mutableListOf()

    override fun addWrackViewer(player: Player) {
        wrackEntities.forEach { it.addViewer(player) }
        viewers.add(player)
    }

    override fun removeWrackViewer(player: Player) {
        wrackEntities.forEach { it.removeViewer(player) }
        viewers.remove(player)
    }

    override fun enqueueSpawnWrack(wrackType: WrackType) {
        spawnQueue.add(wrackType)
    }

    override suspend fun wrackTick(time: Long) {
        tickingWrackEntities.removeIf { !it.tick(time) }

        if (time % 200L == 0L) {
            viewers.removeIf(Player::isValid)
        }

        if (spawnQueue.isNotEmpty()) {
            spawnQueuedWracks(time)
        }
    }

    private suspend fun spawnWrack(wrackType: WrackType, coastSide: CoastSide, time: Long) {
        val world = Bukkit.getIslandsWorld()
        val driftPath = DriftPath.random(islandPos, coastSide, world, plugin)
        val wrackEntity = WrackEntity(wrackType, world, driftPath, time) {
            scope.launch {
                wrackEntities.remove(it)
                tickingWrackEntities.remove(it)
            }
        }
        viewers.forEach(wrackEntity::addViewer)
        tickingWrackEntities.add(wrackEntity)
        wrackEntities.add(wrackEntity)
    }

    private suspend fun spawnQueuedWracks(time: Long) {
        val wrackTypesToSpawn = buildList(spawnQueue.size) {
            while (spawnQueue.isNotEmpty()) {
                add(spawnQueue.removeFirst())
            }
        }

        for (wrackType in wrackTypesToSpawn) {
            val coastSide = CoastSide.entries.random()
            spawnWrack(wrackType, coastSide, time)
        }
    }
}
