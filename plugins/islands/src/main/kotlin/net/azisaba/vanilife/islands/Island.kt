package net.azisaba.vanilife.islands

import io.papermc.paper.math.BlockPosition
import io.papermc.paper.math.Position
import net.azisaba.vanilife.islands.persistence.IslandRepository
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.audience.ForwardingAudience
import net.kyori.adventure.pointer.Pointer
import net.kyori.adventure.pointer.Pointers
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.joml.Vector2fc
import org.joml.Vector3dc
import kotlin.uuid.Uuid

class Island internal constructor(
    override val pos: IslandPos,
    override val ownerUuid: Uuid,
    settings: IslandSettings,
    val world: World,
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

    val minBound: BlockPosition
    val maxBound: BlockPosition

    init {
        val halfW = IslandDefaults.ISLAND_SIZE_X_BLOCKS / 2
        val halfH = IslandDefaults.ISLAND_SIZE_Z_BLOCKS / 2
        val cx = pos.centerBlockX()
        val cz = pos.centerBlockZ()

        minBound = Position.block(cx - halfW, world.minHeight, cx - halfH)
        maxBound = Position.block(cz + halfW, world.maxHeight - 1, cz + halfH)
    }

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

    operator fun contains(pos: Position): Boolean =
        pos.x() in minBound.x()..maxBound.x() && pos.y() in minBound.y()..maxBound.y() && pos.z() in minBound.z()..maxBound.z()

    override fun audiences(): Iterable<Audience> = world.players.filter { it.location in this }

    override fun pointers(): Pointers = super<IslandInfo>.pointers()
}
