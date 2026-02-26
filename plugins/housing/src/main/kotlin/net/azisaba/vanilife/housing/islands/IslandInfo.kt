package net.azisaba.vanilife.housing.islands

import net.azisaba.vanilife.Vanilife
import net.azisaba.vanilife.islands.IslandPos
import net.kyori.adventure.key.Key
import net.kyori.adventure.pointer.Pointer
import net.kyori.adventure.pointer.Pointered
import net.kyori.adventure.pointer.Pointers
import net.kyori.adventure.pointer.PointersSupplier
import net.kyori.adventure.text.Component
import org.joml.Vector2f
import org.joml.Vector2fc
import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.uuid.Uuid

interface IslandInfo : Pointered {
    val pos: IslandPos

    val ownerUuid: Uuid

    val settings: IslandSettings

    override fun pointers(): Pointers = POINTERS_SUPPLIER.view(this)

    companion object {
        val DISPLAY_NAME: Pointer<Component?> = Pointer.pointer(Component::class.java, Key.key(Vanilife.NAMESPACE, "display_name"))
        val SPAWN_OFFSET: Pointer<Vector3dc> = Pointer.pointer(Vector3dc::class.java, Key.key(Vanilife.NAMESPACE, "spawn/offset"))
        val SPAWN_ROTATION: Pointer<Vector2fc> = Pointer.pointer(Vector2fc::class.java, Key.key(Vanilife.NAMESPACE, "spawn/rotation"))

        private val POINTERS_SUPPLIER: PointersSupplier<IslandInfo> = PointersSupplier.builder<IslandInfo>()
            .resolving(DISPLAY_NAME) { it.settings.displayName }
            .resolving(SPAWN_OFFSET) { it.settings.spawnOffset }
            .resolving(SPAWN_ROTATION) { it.settings.spawnRotation }
            .build()
    }
}

interface IslandInfoLookup {
    suspend fun lookupByPos(pos: IslandPos): IslandInfo?

    suspend fun lookupByOwner(ownerUuid: Uuid): IslandInfo?
}

data class IslandSettings(
    val displayName: Component? = null,
    val spawnOffset: Vector3dc = Vector3d(),
    val spawnRotation: Vector2fc = Vector2f(),
)
