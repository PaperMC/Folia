package net.azisaba.vanilife.islands

import net.kyori.adventure.text.Component
import kotlin.uuid.Uuid

data class Island(
    val pos: IslandPos,
    val owner: Uuid,
    val name: Component?,
)
