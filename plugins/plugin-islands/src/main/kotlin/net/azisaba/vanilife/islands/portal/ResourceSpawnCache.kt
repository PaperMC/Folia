package net.azisaba.vanilife.islands.portal

import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import net.azisaba.vanilife.islands.IslandPos
import net.azisaba.vanilife.islands.PortalConfig
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal class ResourceSpawnCache(
    private val plugin: Plugin,
    private val config: PortalConfig,
    private val repository: ResourceSpawnRepository,
) {
    private val memoryCache: MutableMap<CacheKey, ResourceSpawnCacheEntry> = ConcurrentHashMap()
    private val inFlight = ConcurrentHashMap<CacheKey, Deferred<ResourceSpawnCacheEntry>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getOrCompute(
        islandPos: IslandPos,
        world: World,
    ): ResourceSpawnCacheEntry {
        val key = CacheKey(world.key.toString(), world.seed, islandPos.x(), islandPos.z())
        memoryCache[key]?.let {
            touch(key)
            return it
        }

        repository.find(key.worldId, key.seed, key.islandX, key.islandZ)?.let {
            memoryCache[key] = it
            touch(key)
            return it
        }

        val deferred =
            inFlight.computeIfAbsent(key) {
                scope.async {
                    val computed = computeEntry(key, world)
                    repository.upsert(computed)
                }
            }
        return try {
            deferred.await().also { memoryCache[key] = it }
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    suspend fun overwrite(
        islandPos: IslandPos,
        world: World,
        x: Int,
        y: Int,
        z: Int,
    ): ResourceSpawnCacheEntry {
        val now = System.currentTimeMillis()
        val key = CacheKey(world.key.toString(), world.seed, islandPos.x(), islandPos.z())
        val current = repository.find(key.worldId, key.seed, key.islandX, key.islandZ)
        val entry =
            ResourceSpawnCacheEntry(
                worldId = key.worldId,
                seed = key.seed,
                islandX = key.islandX,
                islandZ = key.islandZ,
                spawnX = x,
                spawnY = y,
                spawnZ = z,
                createdAtMillis = current?.createdAtMillis ?: now,
                lastUsedAtMillis = now,
                version = current?.version ?: 1,
                flags = current?.flags,
            )
        val saved = repository.upsert(entry)
        memoryCache[key] = saved
        return saved
    }

    suspend fun show(
        world: World,
        seed: Long,
        islandX: Int,
        islandZ: Int,
    ): ResourceSpawnCacheEntry? = repository.find(world.key.toString(), seed, islandX, islandZ)

    suspend fun invalidate(
        world: World,
        seed: Long,
        islandX: Int?,
        islandZ: Int?,
    ) {
        if (islandX != null && islandZ != null) {
            repository.delete(world.key.toString(), seed, islandX, islandZ)
            memoryCache.remove(CacheKey(world.key.toString(), seed, islandX, islandZ))
            return
        }

        repository.deleteBySeed(world.key.toString(), seed)
        memoryCache.keys.removeIf { it.worldId == world.key.toString() && it.seed == seed }
    }

    private fun touch(key: CacheKey) {
        val now = System.currentTimeMillis()
        val cached = memoryCache[key] ?: return
        memoryCache[key] = cached.copy(lastUsedAtMillis = now)
        scope.launch {
            repository.touchLastUsed(key.worldId, key.seed, key.islandX, key.islandZ)
        }
    }

    private suspend fun computeEntry(
        key: CacheKey,
        world: World,
    ): ResourceSpawnCacheEntry {
        val now = System.currentTimeMillis()
        val (x, z) = computeDeterministicXZ(key.islandX, key.islandZ, key.seed)
        val y = highestY(world, x, z)
        plugin.slF4JLogger.info("Computed spawn loc -> {} / {} / {}", x,y,z)
        val safe = findSafeLocation(world, x, y, z, config.safeSearchRadius) ?: Location(world, x + 0.5, y.toDouble(), z + 0.5)
        plugin.slF4JLogger.info("safe spawn loc -> {}", safe.toString())

        return ResourceSpawnCacheEntry(
            worldId = key.worldId,
            seed = key.seed,
            islandX = key.islandX,
            islandZ = key.islandZ,
            spawnX = safe.blockX,
            spawnY = safe.blockY,
            spawnZ = safe.blockZ,
            createdAtMillis = now,
            lastUsedAtMillis = now,
            version = 1,
            flags = null,
        )
    }

    private suspend fun highestY(
        world: World,
        x: Int,
        z: Int,
    ): Int {
        val location = Location(world, x.toDouble(), world.minHeight.toDouble(), z.toDouble())
        return withContext(plugin.regionDispatcher(location)) {
            world.getChunkAtAsync(x shr 4, z shr 4, true).await()
            world.getHighestBlockYAt(x, z, HeightMap.RESOURCE_OVERWORLD_OCEAN_FLOOR) + 1
        }
    }

    suspend fun findSafeLocation(
        world: World,
        x: Int,
        y: Int,
        z: Int,
        radius: Int,
    ): Location? {
        return withContext(plugin.regionDispatcher(Location(world, x.toDouble(), y.toDouble(), z.toDouble()))) {
            for (r in 0..radius) {
                for (dx in -r..r) {
                    for (dz in -r..r) {
                        val cx = x + dx
                        val cz = z + dz
                        world.getChunkAtAsync(cx shr 4, cz shr 4, true).await()
                        val cy = world.getHighestBlockYAt(cx, cz, HeightMap.RESOURCE_OVERWORLD_MOTION_BLOCKING) + 1
                        if (isSafe(world, cx, cy, cz)) {
                            return@withContext Location(world, cx + 0.5, cy.toDouble(), cz + 0.5)
                        }
                    }
                }
            }
            null
        }
    }

    private fun isSafe(
        world: World,
        x: Int,
        y: Int,
        z: Int,
    ): Boolean {
        val feet = world.getBlockAt(x, y, z)
        val head = world.getBlockAt(x, y + 1, z)
        val below = world.getBlockAt(x, y - 1, z)
        if (!feet.isEmpty || !head.isEmpty) return false
        if (!below.type.isSolid) return false
        if (below.type == Material.LAVA || below.type == Material.MAGMA_BLOCK) return false
        if (below.isLiquid) return false
        return true
    }

    private fun computeDeterministicXZ(
        islandX: Int,
        islandZ: Int,
        worldSeed: Long,
    ): Pair<Int, Int> {
        var h = islandX.toLong() * 0x9E3779B97F4A7C15UL.toLong() xor (islandZ.toLong() * 0xC2B2AE3D27D4EB4FUL.toLong()) xor worldSeed
        if (h == 0L) h = 0xDEADBEEFL
        val random = Random(h)
        val radius = config.baseRadius + random.nextDouble() * config.radiusVariance
        val angle = random.nextDouble() * 2.0 * PI
        val x = (cos(angle) * radius).roundToInt()
        val z = (sin(angle) * radius).roundToInt()
        return x to z
    }

    private data class CacheKey(
        val worldId: String,
        val seed: Long,
        val islandX: Int,
        val islandZ: Int,
    )
}
