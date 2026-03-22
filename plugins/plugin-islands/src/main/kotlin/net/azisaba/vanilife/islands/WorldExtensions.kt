package net.azisaba.vanilife.islands

import net.azisaba.vanilife.world.IslandPos
import net.azisaba.vanilife.world.IslandsWorld
import org.koin.core.context.GlobalContext

suspend fun IslandsWorld.getIslandAt(pos: IslandPos): Island? {
    val manager = GlobalContext.get().get<IslandManager>()
    return manager.lookupByPos(pos)
}
