package net.azisaba.vanilife.islands.portal

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

internal data class ResourceSpawnCacheEntry(
    val worldId: String,
    val seed: Long,
    val islandX: Int,
    val islandZ: Int,
    val spawnX: Int,
    val spawnY: Int,
    val spawnZ: Int,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long,
    val version: Int,
    val flags: String?,
)

internal interface ResourceSpawnRepository {
    suspend fun find(
        worldId: String,
        seed: Long,
        islandX: Int,
        islandZ: Int,
    ): ResourceSpawnCacheEntry?

    suspend fun upsert(entry: ResourceSpawnCacheEntry): ResourceSpawnCacheEntry

    suspend fun touchLastUsed(
        worldId: String,
        seed: Long,
        islandX: Int,
        islandZ: Int,
    )

    suspend fun deleteBySeed(
        worldId: String,
        seed: Long,
    )

    suspend fun delete(
        worldId: String,
        seed: Long,
        islandX: Int,
        islandZ: Int,
    )
}

internal class ExposedResourceSpawnRepository(
    private val database: Database,
) : ResourceSpawnRepository {
    override suspend fun find(
        worldId: String,
        seed: Long,
        islandX: Int,
        islandZ: Int,
    ): ResourceSpawnCacheEntry? =
        suspendTransaction(database) {
            ResourceSpawnTable
                .selectAll()
                .where {
                    (ResourceSpawnTable.worldId eq worldId) and
                        (ResourceSpawnTable.seed eq seed) and
                        (ResourceSpawnTable.islandX eq islandX) and
                        (ResourceSpawnTable.islandZ eq islandZ)
                }.firstOrNull()
                ?.toEntry()
        }

    override suspend fun upsert(entry: ResourceSpawnCacheEntry): ResourceSpawnCacheEntry =
        suspendTransaction(database) {
            val updated =
                ResourceSpawnTable.update(
                    where = {
                        (ResourceSpawnTable.worldId eq entry.worldId) and
                            (ResourceSpawnTable.seed eq entry.seed) and
                            (ResourceSpawnTable.islandX eq entry.islandX) and
                            (ResourceSpawnTable.islandZ eq entry.islandZ)
                    },
                ) {
                    it[spawnX] = entry.spawnX
                    it[spawnY] = entry.spawnY
                    it[spawnZ] = entry.spawnZ
                    it[lastUsedAt] = entry.lastUsedAtMillis
                    it[version] = entry.version
                    it[flags] = entry.flags
                }
            if (updated == 0) {
                ResourceSpawnTable.insert {
                    it[worldId] = entry.worldId
                    it[seed] = entry.seed
                    it[islandX] = entry.islandX
                    it[islandZ] = entry.islandZ
                    it[spawnX] = entry.spawnX
                    it[spawnY] = entry.spawnY
                    it[spawnZ] = entry.spawnZ
                    it[createdAt] = entry.createdAtMillis
                    it[lastUsedAt] = entry.lastUsedAtMillis
                    it[version] = entry.version
                    it[flags] = entry.flags
                }
            }
            entry
        }

    override suspend fun touchLastUsed(
        worldId: String,
        seed: Long,
        islandX: Int,
        islandZ: Int,
    ) {
        suspendTransaction(database) {
            ResourceSpawnTable.update(
                where = {
                    (ResourceSpawnTable.worldId eq worldId) and
                        (ResourceSpawnTable.seed eq seed) and
                        (ResourceSpawnTable.islandX eq islandX) and
                        (ResourceSpawnTable.islandZ eq islandZ)
                },
            ) {
                it[lastUsedAt] = System.currentTimeMillis()
            }
        }
    }

    override suspend fun deleteBySeed(
        worldId: String,
        seed: Long,
    ) {
        suspendTransaction(database) {
            ResourceSpawnTable.deleteWhere {
                (ResourceSpawnTable.worldId eq worldId) and (ResourceSpawnTable.seed eq seed)
            }
        }
    }

    override suspend fun delete(
        worldId: String,
        seed: Long,
        islandX: Int,
        islandZ: Int,
    ) {
        suspendTransaction(database) {
            ResourceSpawnTable.deleteWhere {
                (ResourceSpawnTable.worldId eq worldId) and
                    (ResourceSpawnTable.seed eq seed) and
                    (ResourceSpawnTable.islandX eq islandX) and
                    (ResourceSpawnTable.islandZ eq islandZ)
            }
        }
    }

    private fun ResultRow.toEntry(): ResourceSpawnCacheEntry =
        ResourceSpawnCacheEntry(
            worldId = get(ResourceSpawnTable.worldId),
            seed = get(ResourceSpawnTable.seed),
            islandX = get(ResourceSpawnTable.islandX),
            islandZ = get(ResourceSpawnTable.islandZ),
            spawnX = get(ResourceSpawnTable.spawnX),
            spawnY = get(ResourceSpawnTable.spawnY),
            spawnZ = get(ResourceSpawnTable.spawnZ),
            createdAtMillis = get(ResourceSpawnTable.createdAt),
            lastUsedAtMillis = get(ResourceSpawnTable.lastUsedAt),
            version = get(ResourceSpawnTable.version),
            flags = get(ResourceSpawnTable.flags),
        )
}

object ResourceSpawnTable : Table("resource_spawn_cache") {
    val worldId: Column<String> = text("world_id")
    val seed: Column<Long> = long("seed")
    val islandX: Column<Int> = integer("island_x")
    val islandZ: Column<Int> = integer("island_z")
    val spawnX: Column<Int> = integer("spawn_x")
    val spawnY: Column<Int> = integer("spawn_y")
    val spawnZ: Column<Int> = integer("spawn_z")
    val createdAt: Column<Long> = long("created_at").default(0L)
    val lastUsedAt: Column<Long> = long("last_used_at").default(0L)
    val version: Column<Int> = integer("version").default(1)
    val flags: Column<String?> = text("flags").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(worldId, seed, islandX, islandZ, name = "pk_resource_spawn")
}
