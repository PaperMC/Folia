package net.azisaba.vanilife.housing.islands

import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.shynixn.mccoroutine.folia.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.tofaa.entitylib.tick.TickContainer
import net.azisaba.vanilife.housing.waves.WavePos
import net.azisaba.vanilife.housing.waves.WrapperWave
import net.azisaba.vanilife.housing.waves.wrack.WrackType
import net.azisaba.vanilife.islands.IslandPos
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import kotlin.random.Random

internal class IslandWaveManager(private val world: World, private val islandPos: IslandPos) : TickContainer<WrapperWave, Unit>() {
    private var time: Long = 0L
    private var job: Job? = null

    fun startWith(plugin: Plugin): IslandWaveManager {
        if (job?.isActive == true) return this

        job = plugin.launch {
            addAllWaves()
            try {
                while (isActive) {
                    time = (time + 1) % Long.MAX_VALUE

                    tick(time)
                    if (time % 20L == 0L) {
                        syncViewersTick()
                    }

                    delay(50L)
                }
            } finally {
                removeAllWaves()
            }
        }

        return this
    }

    override fun tick(time: Long) {
        if (Random.nextDouble() < 0.05) {
            addFlotsam(WrackType("bottle", listOf(org.bukkit.inventory.ItemStack.of(Material.BREAD)).iterator()))
        }

        super.tick(time)
    }

    fun addFlotsam(wrackType: WrackType) {
        if (tickables.isEmpty()) return

        val min = tickables.minOf(WrapperWave::pendingFlotsamCount)
        val candidates = tickables.filter { it.pendingFlotsamCount == min }
        val selected = candidates.random()

        selected.enqueueFlotsam(wrackType)
    }

    private fun addAllWaves() {
        for (wavePos in WavePos.Companion.posSet(islandPos)) {
            val wrapperWave = WrapperWave(wavePos)
            wrapperWave.spawn(wavePos.location())
            addTickable(wrapperWave)
        }
    }

    private fun removeAllWaves() {
        for (wrapperWave in tickables.toList()) {
            wrapperWave.remove()
            removeTickable(wrapperWave)
        }
    }

    private fun syncViewersTick() {
        val playerUuids = world.players
            .asSequence()
            .filter { IslandPos.fromBlockPos(it.location.blockX, it.location.blockZ) == islandPos }
            .map(Player::getUniqueId)
            .toList()

        for (wrapperWave in tickables) {
            wrapperWave.viewers
                .filter { it !in playerUuids }
                .toList()
                .forEach(wrapperWave::removeViewer)

            playerUuids
                .filter { it !in wrapperWave.viewers }
                .forEach(wrapperWave::addViewer)
        }
    }
}
