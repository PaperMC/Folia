package net.azisaba.vanilife.islands

import net.azisaba.vanilife.islands.storage.PrimaryIslandData
import java.util.*

interface IslandInfo {
    val pos: IslandPos

    val ownerUuid: UUID

    val primaryData: PrimaryIslandData
}

interface IslandInfoLookup {
    suspend fun lookupByPos(islandPos: IslandPos): IslandInfo?

    suspend fun lookupByOwner(ownerUuid: UUID): IslandInfo?
}

data class IslandSummary(
    override val pos: IslandPos,
    override val ownerUuid: UUID,
    override val primaryData: PrimaryIslandData
) : IslandInfo
