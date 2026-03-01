package net.azisaba.vanilife.islands.storage

import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.islands.IslandDefaults
import net.azisaba.vanilife.islands.IslandPos
import net.kyori.adventure.key.Key
import net.kyori.adventure.pointer.Pointer
import net.kyori.adventure.pointer.Pointered
import net.kyori.adventure.pointer.Pointers
import net.kyori.adventure.pointer.PointersSupplier
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.joml.Vector2f
import org.joml.Vector2fc
import org.joml.Vector3d
import org.joml.Vector3dc

sealed interface PrimaryIslandData : Pointered {
    val displayName: Component?

    val spawnOffset: Vector3dc

    val spawnRotation: Vector2fc

    override fun pointers(): Pointers = POINTER_SUPPLIER.view(this)

    companion object {
        val DISPLAY_NAME: Pointer<Component?> =
            Pointer.pointer(Component::class.java, Key.key(Vanilife.NAMESPACE, "display_name"))
        val SPAWN_OFFSET: Pointer<Vector3dc> =
            Pointer.pointer(Vector3dc::class.java, Key.key(Vanilife.NAMESPACE, "spawn_pos/offset"))
        val SPAWN_ROTATION: Pointer<Vector2fc> =
            Pointer.pointer(Vector2fc::class.java, Key.key(Vanilife.NAMESPACE, "spawn_pos/rotation"))

        private val POINTER_SUPPLIER: PointersSupplier<PrimaryIslandData> =
            PointersSupplier.builder<PrimaryIslandData>()
                .resolving(DISPLAY_NAME, PrimaryIslandData::displayName)
                .resolving(SPAWN_OFFSET, PrimaryIslandData::spawnOffset)
                .resolving(SPAWN_ROTATION, PrimaryIslandData::spawnRotation)
                .build()
    }

    data class Snapshot(
        override val displayName: Component? = null,
        override val spawnOffset: Vector3dc = Vector3d(),
        override val spawnRotation: Vector2fc = Vector2f(),
    ) : PrimaryIslandData

    class Writable internal constructor(
        displayName: Component?,
        spawnOffset: Vector3dc,
        spawnRotation: Vector2fc,
        private val islandPos: IslandPos,
        private val repository: IslandRepository
    ) : PrimaryIslandData {
        override var displayName: Component? = displayName
            private set

        override var spawnOffset: Vector3dc = spawnOffset
            private set

        override var spawnRotation: Vector2fc = spawnRotation
            private set

        suspend fun updateDisplayName(displayName: Component) {
            repository.updateDisplayName(islandPos, displayName)
            this.displayName = displayName
        }

        suspend fun updateSpawnPoint(offset: Vector3dc, rotation: Vector2fc) {
            repository.updateSpawnOffset(islandPos, offset)
            repository.updateSpawnRotation(islandPos, rotation)
            this.spawnOffset = offset
            this.spawnRotation = rotation
        }
    }
}

fun PrimaryIslandData.resolveSpawnPoint(islandPos: IslandPos): Location {
    val centerX = islandPos.centerBlockX()
    val centerY = IslandDefaults.MIN_Y + IslandDefaults.HEIGHT / 2
    val centerZ = islandPos.centerBlockZ()
    return Location(
        Bukkit.getIslandsWorld(),
        centerX + spawnOffset.x(),
        centerY + spawnOffset.y(),
        centerZ + spawnOffset.z(),
        spawnRotation.x(),
        spawnRotation.y()
    )
}

internal fun PrimaryIslandData.asWritable(
    islandPos: IslandPos,
    repository: IslandRepository,
): PrimaryIslandData.Writable {
    (this as? PrimaryIslandData.Writable)?.let { return it }
    return PrimaryIslandData.Writable(
        displayName,
        spawnOffset,
        spawnRotation,
        islandPos,
        repository,
    )
}
