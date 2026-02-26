package net.azisaba.vanilife.housing.islands

import net.azisaba.vanilife.housing.persistence.IslandRepository
import net.azisaba.vanilife.islands.IslandDefaults
import net.azisaba.vanilife.islands.IslandPos
import org.bukkit.Bukkit
import org.bukkit.World
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class IslandAccess(private val islandRepository: IslandRepository) : IslandInfoLookup {
    private val world: World = Bukkit.getWorld(IslandDefaults.WORLD_KEY)!!

    private val map: MutableMap<IslandPos, Island> = ConcurrentHashMap()
    private val posByOwner: MutableMap<Uuid, IslandPos> = ConcurrentHashMap()

    override suspend fun lookupByPos(pos: IslandPos): Island? {
        map[pos]?.let { return it }
        val info = islandRepository.lookupByPos(pos) ?: return null
        return getOrCreateInstance(info)
    }

    override suspend fun lookupByOwner(ownerUuid: Uuid): Island? {
        posByOwner[ownerUuid]?.let { pos -> return lookupByPos(pos) }
        val info = islandRepository.lookupByOwner(ownerUuid) ?: return null
        return getOrCreateInstance(info)
    }

    suspend fun lookupOrCreateByOwner(ownerUuid: Uuid): Island {
        lookupByOwner(ownerUuid)?.let { return it }
        val info = islandRepository.insert(ownerUuid)
        return getOrCreateInstance(info)
    }

    private fun getOrCreateInstance(info: IslandInfo): Island {
        posByOwner.putIfAbsent(info.ownerUuid, info.pos)
        return map.computeIfAbsent(info.pos) { pos ->
            Island(pos, info.ownerUuid, info.settings, world, islandRepository)
        }
    }
}