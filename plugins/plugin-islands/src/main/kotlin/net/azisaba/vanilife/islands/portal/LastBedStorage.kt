package net.azisaba.vanilife.islands.portal

import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class LastBedStorage(private val repository: LastBedRepository) {
    private val memory: MutableMap<Pair<String, UUID>, LastBedEntry> = ConcurrentHashMap()

    suspend fun setLastBed(worldId: String, playerUuid: UUID, location: Location) {
        val entry = LastBedEntry(
            worldId = worldId,
            playerUuid = playerUuid,
            x = location.blockX,
            y = location.blockY,
            z = location.blockZ,
            yaw = location.yaw,
            pitch = location.pitch,
            updatedAtMillis = System.currentTimeMillis(),
        )
        memory[worldId to playerUuid] = entry
        repository.upsert(entry)
    }

    suspend fun getLastBed(worldId: String, playerUuid: UUID): LastBedEntry? {
        memory[worldId to playerUuid]?.let { return it }
        val loaded = repository.find(worldId, playerUuid) ?: return null
        memory[worldId to playerUuid] = loaded
        return loaded
    }

    suspend fun clear(worldId: String, playerUuid: UUID) {
        memory.remove(worldId to playerUuid)
        repository.delete(worldId, playerUuid)
    }
}
