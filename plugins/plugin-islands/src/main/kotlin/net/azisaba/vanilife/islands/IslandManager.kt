package net.azisaba.vanilife.islands

import net.azisaba.vanilife.islands.storage.IslandRepository
import net.azisaba.vanilife.islands.storage.asWritable
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class IslandManager(
    private val repository: IslandRepository,
    private val world: World,
    private val plugin: Plugin,
) : IslandInfoLookup {
    private val islandsByPos: MutableMap<IslandPos, Island> = ConcurrentHashMap()
    private val posByOwner: MutableMap<UUID, IslandPos> = ConcurrentHashMap()

    override suspend fun lookupByPos(islandPos: IslandPos): Island? {
        islandsByPos[islandPos]?.let { return it }
        val summary = repository.lookupByPos(islandPos) ?: return null
        return getOrCreateInstance(summary)
    }

    override suspend fun lookupByOwner(ownerUuid: UUID): Island? {
        posByOwner[ownerUuid]?.let { islandPos -> return lookupByPos(islandPos) }
        val summary = repository.lookupByOwner(ownerUuid) ?: return null
        return getOrCreateInstance(summary)
    }

    suspend fun lookupOrCreateByOwner(ownerUuid: UUID): Island {
        lookupByOwner(ownerUuid)?.let { return it }
        val summary = repository.insert(ownerUuid)
        return getOrCreateInstance(summary)
    }

    private fun getOrCreateInstance(summary: IslandSummary): Island {
        posByOwner.putIfAbsent(summary.ownerUuid, summary.pos)
        return islandsByPos.computeIfAbsent(summary.pos) { islandPos ->
            Island(islandPos, summary.ownerUuid, summary.primaryData.asWritable(islandPos, repository), plugin)
        }
    }
}
