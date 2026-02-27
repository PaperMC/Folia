package net.azisaba.vanilife.housing.islands

import net.azisaba.vanilife.housing.persistence.IslandRepository
import net.azisaba.vanilife.housing.waves.wrack.WrackType
import net.azisaba.vanilife.islands.IslandDefaults
import net.azisaba.vanilife.islands.IslandPos
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.audience.ForwardingAudience
import net.kyori.adventure.pointer.Pointer
import net.kyori.adventure.pointer.Pointers
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.joml.Vector2fc
import org.joml.Vector3dc
import kotlin.uuid.Uuid

class Island internal constructor(
    plugin: Plugin,
    val world: World,
    override val pos: IslandPos,
    override val ownerUuid: Uuid,
    settings: IslandSettings,
    private val islandRepository: IslandRepository,
) : ForwardingAudience, IslandInfo {
    override var settings: IslandSettings = settings
        private set

    val spawnPoint: Location
        get() {
            val spawnPoint = Location(
                world,
                pos.centerBlockX().toDouble(),
                IslandDefaults.SEA_LEVEL + 1.0,
                pos.centerBlockZ().toDouble()
            )
            spawnPoint.add(settings.spawnOffset.x(), settings.spawnOffset.y(), settings.spawnOffset.z())
            spawnPoint.addRotation(settings.spawnRotation.x(), settings.spawnRotation.y())
            return spawnPoint
        }

    private val waveManager: IslandWaveManager = IslandWaveManager(world, pos).startWith(plugin)

    suspend fun <T> set(pointer: Pointer<T>, value: T) = when (value) {
        IslandInfo.DISPLAY_NAME -> setDisplayName(value as Component?)
        IslandInfo.SPAWN_OFFSET -> setSpawnOffset(value as Vector3dc)
        IslandInfo.SPAWN_ROTATION -> setSpawnRotation(value as Vector2fc)
        else -> throw IllegalArgumentException("Invalid island pointer: $pointer")
    }

    suspend fun setDisplayName(displayName: Component?) {
        islandRepository.updateDisplayName(pos, displayName)
        settings = settings.copy(displayName = displayName)
    }

    suspend fun setSpawnOffset(spawnOffset: Vector3dc) {
        islandRepository.updateSpawnOffset(pos, spawnOffset)
        settings = settings.copy(spawnOffset = spawnOffset)
    }

    suspend fun setSpawnRotation(spawnRotation: Vector2fc) {
        islandRepository.updateSpawnRotation(pos, spawnRotation)
        settings = settings.copy(spawnRotation = spawnRotation)
    }

    override fun audiences(): Iterable<Audience> = world.players.filter { pos.contains(it.location) }

    override fun pointers(): Pointers = super<IslandInfo>.pointers()

    fun addFlotsam(wrackType: WrackType) {
        waveManager.addFlotsam(wrackType)
    }
}
