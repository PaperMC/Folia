package net.azisaba.vanilife.islands.waves

import net.azisaba.vanilife.islands.IslandPos
import java.util.*

interface WaveAccessor {
    fun addWaveViewer(uuid: UUID)

    fun removeWaveViewer(uuid: UUID)

    fun waveTick(time: Long)
}

internal class IslandWaveAccessor(islandPos: IslandPos) : WaveAccessor {
    private val wavesByPos: MutableMap<WavePos, WrapperWave> = HashMap(WavePos.WAVES_PER_ISLAND)

    init {
        for (wavePos in WavePos.posSet(islandPos)) {
            val wrapperWave = WrapperWave(wavePos)
            wrapperWave.spawn(wavePos.location())
            wavesByPos[wavePos] = wrapperWave
        }
    }

    override fun addWaveViewer(uuid: UUID) {
        wavesByPos.values.forEach { it.addViewer(uuid) }
    }

    override fun removeWaveViewer(uuid: UUID) {
        wavesByPos.values.forEach { it.removeViewer(uuid) }
    }

    override fun waveTick(time: Long) {
        wavesByPos.values.forEach { it.tick(time) }
    }
}
