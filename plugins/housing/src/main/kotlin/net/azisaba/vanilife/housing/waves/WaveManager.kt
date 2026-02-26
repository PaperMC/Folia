package net.azisaba.vanilife.housing.waves

import com.github.retrooper.packetevents.PacketEvents
import com.github.shynixn.mccoroutine.folia.launch
import kotlinx.coroutines.delay
import me.tofaa.entitylib.tick.TickContainer
import net.azisaba.vanilife.housing.Main
import net.azisaba.vanilife.islands.IslandDefaults
import net.azisaba.vanilife.islands.IslandPos
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.*

internal fun Main.setupWaveManager() {
    WaveManager(this).launch()
}

class WaveManager(private val plugin: Plugin) : TickContainer<WrapperWave, Unit>() {
    private var ticks: Long = 0L

    private val wavesByPos = HashMap<WavePos, WrapperWave>(WavePos.WAVES_PER_ISLAND)

    fun launch() {
        if (PacketEvents.getAPI() == null) {
            plugin.logger.warning("PacketEvents API was not found. Shore wave animation is disabled.")
            return
        }
        plugin.launch {
            while (true) {
                tick(ticks++)
                delay(50L)
            }
        }
    }

    override fun tick(time: Long) {
        val world = Bukkit.getWorld(IslandDefaults.WORLD_KEY) ?: return
        val players = world.players
        if (players.isEmpty()) {
            tickables.toList().forEach(::removeTickable)
            return
        }

        val playersByIslandPos = players.groupByTo(HashMap(players.size)) { player ->
            IslandPos.fromBlockPos(player.location.blockX, player.location.blockZ)
        }
        val requiredWavePosSet = buildSet(playersByIslandPos.size * WavePos.WAVES_PER_ISLAND) {
            for (islandPos in playersByIslandPos.keys) {
                addAll(WavePos.posSet(islandPos))
            }
        }

        removeUnusedWaves(requiredWavePosSet)
        addMissingWaves(requiredWavePosSet)

        updateViewers(playersByIslandPos.mapValues { it.value.map(Player::getUniqueId) })

        super.tick(time)
    }

    override fun removeTickable(tickable: WrapperWave): Boolean {
        if (!super.removeTickable(tickable)) return false
        wavesByPos -= tickable.pos
        tickable.remove()
        return true
    }

    private fun addMissingWaves(requiredWavePosSet: Set<WavePos>) {
        requiredWavePosSet.filter { it !in wavesByPos }.forEach { pos ->
            val wrapperWave = WrapperWave(pos)
            wrapperWave.spawn(pos.location())
            addTickable(wrapperWave)
            wavesByPos[pos] = wrapperWave
        }
    }

    private fun removeUnusedWaves(requiredWavePosSet: Set<WavePos>) {
        val unusedWaves = wavesByPos.values.filter { it.pos !in requiredWavePosSet }
        unusedWaves.forEach(::removeTickable)
    }

    private fun updateViewers(playerUuidsByIslandPos: Map<IslandPos, Collection<UUID>>) {
        for (wrapperWave in tickables) {
            val islandPos = wrapperWave.pos.islandPos
            val islandPlayers = playerUuidsByIslandPos[islandPos] ?: emptyList()
            wrapperWave.viewers.filter { it !in islandPlayers }.toList().forEach(wrapperWave::removeViewer)
            islandPlayers.filter { it !in wrapperWave.viewers }.forEach { wrapperWave.addViewer(it) }
        }
    }
}
