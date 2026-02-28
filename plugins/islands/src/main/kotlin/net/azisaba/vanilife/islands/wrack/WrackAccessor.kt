package net.azisaba.vanilife.islands.wrack

import com.github.shynixn.mccoroutine.folia.scope
import kotlinx.coroutines.launch
import net.azisaba.vanilife.islands.CoastSide
import net.azisaba.vanilife.islands.IslandPos
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

interface WrackAccessor {
    fun addWrackViewer(player: Player)

    fun removeWrackViewer(player: Player)

    fun enqueueSpawnWrack(wrackType: WrackType)

    fun wrackTick(time: Long)
}

internal class IslandWrackAccessor(val islandPos: IslandPos, val world: World, val plugin: Plugin) : WrackAccessor {
    private val scope = plugin.scope

    private val wrackEntities: MutableList<WrackEntity> = mutableListOf()
    private val tickingWrackEntities: MutableList<WrackEntity> = mutableListOf()
    private val viewers: MutableSet<Player> = mutableSetOf()
    private val spawnQueue: ArrayDeque<WrackType> = ArrayDeque()

    override fun addWrackViewer(player: Player) {
        scope.launch {
            wrackEntities.forEach { it.addViewer(player) }
            viewers.add(player)
        }
    }

    override fun removeWrackViewer(player: Player) {
        scope.launch {
            wrackEntities.forEach { it.removeViewer(player) }
            viewers.remove(player)
        }
    }

    override fun enqueueSpawnWrack(wrackType: WrackType) {
        scope.launch {
            spawnQueue.add(wrackType)
        }
    }

    override fun wrackTick(time: Long) {
        scope.launch {
            tickingWrackEntities.removeIf { !it.tick(time) }

            if (spawnQueue.isEmpty()) return@launch

            val toSpawn = ArrayList<WrackType>(spawnQueue.size)
            while (spawnQueue.isNotEmpty()) {
                toSpawn.add(spawnQueue.removeFirst())
            }

            for (wrackType in toSpawn) {
                val coastSide = CoastSide.entries.random()
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
        }
    }
}
