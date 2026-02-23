package net.azisaba.vanilife.islands

import net.azisaba.vanilife.util.component
import net.kyori.adventure.text.Component
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

interface IslandRepository {
    suspend fun create(owner: Uuid): Island

    suspend fun findByOwner(owner: Uuid): Island?

    suspend fun updateName(pos: IslandPos, name: Component)
}

internal class ExposedIslandRepository(private val database: Database) : IslandRepository {
    override suspend fun create(owner: Uuid): Island = suspendTransaction(database) {
        val long = IslandsTable.insertAndGetId {
            it[IslandsTable.owner] = owner
        }.value
        Island(longToPos(long), owner, null)
    }

    override suspend fun findByOwner(owner: Uuid): Island? = suspendTransaction(database) {
        IslandsTable
            .selectAll()
            .where { IslandsTable.owner eq owner }
            .firstOrNull()
            ?.let { row ->
                val long = row[IslandsTable.id].value
                val name = row[IslandsTable.name]
                Island(longToPos(long), owner, name)
            }
    }

    override suspend fun updateName(pos: IslandPos, name: Component) = suspendTransaction(database) {
        val long = posToLong(pos)
        IslandsTable.update(where = { IslandsTable.id eq long }) {
            it[IslandsTable.name] = name
        }
        Unit
    }

    private companion object {
        const val POS_WIDTH: Long = 4096L

        fun longToPos(long: Long): IslandPos {
            val id0 = long - 1
            val x = (id0 % POS_WIDTH).toInt()
            val z = (id0 / POS_WIDTH).toInt()
            return IslandPos(x, z)
        }

        fun posToLong(pos: IslandPos): Long =
            (pos.z().toLong() * POS_WIDTH + pos.x().toLong()) + 1L
    }

    object IslandsTable : LongIdTable(name = "islands", columnName = "pos") {
        val owner: Column<Uuid> = uuid("owner").uniqueIndex()
        val name: Column<Component?> = component("name").nullable()
    }
}
