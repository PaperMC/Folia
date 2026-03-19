package net.azisaba.vanilife.islands.portal

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

internal data class LastBedEntry(
    val worldId: String,
    val playerUuid: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    val yaw: Float,
    val pitch: Float,
    val updatedAtMillis: Long,
)

internal interface LastBedRepository {
    suspend fun find(
        worldId: String,
        playerUuid: UUID,
    ): LastBedEntry?

    suspend fun upsert(entry: LastBedEntry): LastBedEntry

    suspend fun delete(
        worldId: String,
        playerUuid: UUID,
    )
}

internal class ExposedLastBedRepository(
    private val database: Database,
) : LastBedRepository {
    override suspend fun find(
        worldId: String,
        playerUuid: UUID,
    ): LastBedEntry? =
        suspendTransaction(database) {
            LastBedTable
                .selectAll()
                .where { (LastBedTable.worldId eq worldId) and (LastBedTable.playerUuid eq playerUuid) }
                .firstOrNull()
                ?.toEntry()
        }

    override suspend fun upsert(entry: LastBedEntry): LastBedEntry =
        suspendTransaction(database) {
            val updated =
                LastBedTable.update(
                    where = { (LastBedTable.worldId eq entry.worldId) and (LastBedTable.playerUuid eq entry.playerUuid) },
                ) {
                    it[x] = entry.x
                    it[y] = entry.y
                    it[z] = entry.z
                    it[yaw] = entry.yaw
                    it[pitch] = entry.pitch
                    it[updatedAt] = entry.updatedAtMillis
                }
            if (updated == 0) {
                LastBedTable.insert {
                    it[worldId] = entry.worldId
                    it[playerUuid] = entry.playerUuid
                    it[x] = entry.x
                    it[y] = entry.y
                    it[z] = entry.z
                    it[yaw] = entry.yaw
                    it[pitch] = entry.pitch
                    it[updatedAt] = entry.updatedAtMillis
                }
            }
            entry
        }

    override suspend fun delete(
        worldId: String,
        playerUuid: UUID,
    ) {
        suspendTransaction(database) {
            LastBedTable.deleteWhere { (LastBedTable.worldId eq worldId) and (LastBedTable.playerUuid eq playerUuid) }
        }
    }

    private fun ResultRow.toEntry(): LastBedEntry =
        LastBedEntry(
            worldId = get(LastBedTable.worldId),
            playerUuid = get(LastBedTable.playerUuid),
            x = get(LastBedTable.x),
            y = get(LastBedTable.y),
            z = get(LastBedTable.z),
            yaw = get(LastBedTable.yaw),
            pitch = get(LastBedTable.pitch),
            updatedAtMillis = get(LastBedTable.updatedAt),
        )
}

object LastBedTable : Table("resource_last_bed") {
    val worldId: Column<String> = text("world_id")
    val playerUuid: Column<UUID> = javaUUID("player_uuid")
    val x: Column<Int> = integer("x")
    val y: Column<Int> = integer("y")
    val z: Column<Int> = integer("z")
    val yaw: Column<Float> = float("yaw")
    val pitch: Column<Float> = float("pitch")
    val updatedAt: Column<Long> = long("updated_at")

    override val primaryKey: PrimaryKey = PrimaryKey(worldId, playerUuid, name = "pk_resource_last_bed")
}
