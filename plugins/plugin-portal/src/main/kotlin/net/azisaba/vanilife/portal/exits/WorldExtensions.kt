package net.azisaba.vanilife.portal.exits

import io.papermc.paper.math.BlockPosition
import org.bukkit.OfflinePlayer
import org.bukkit.World

fun World.getExitAnchor(player: OfflinePlayer): ExitAnchor? {
    val storage = ExitAnchorStorage(this)
    return storage.getExitAnchor(player.uniqueId)
}

fun World.setExitAnchor(player: OfflinePlayer, type: ExitAnchor.Type, position: BlockPosition) {
    val storage = ExitAnchorStorage(this)
    storage.setExitAnchor(player.uniqueId, type, position)
}

fun World.clearExitAnchor(player: OfflinePlayer) {
    val storage = ExitAnchorStorage(this)
    storage.clearExitAnchor(player.uniqueId)
}
