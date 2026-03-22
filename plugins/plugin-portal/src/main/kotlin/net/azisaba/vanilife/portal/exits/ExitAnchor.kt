package net.azisaba.vanilife.portal.exits

import io.papermc.paper.math.BlockPosition
import io.papermc.paper.math.Position
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key
import net.kyori.adventure.key.Keyed
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.RespawnAnchor
import org.bukkit.entity.Player

data class ExitAnchor(val type: Type, val position: BlockPosition) {
    fun teleportOrClear(world: World, player: Player): Boolean {
        val anchorBlock = world.getBlockAt(position.blockX(), position.blockY(), position.blockZ())
            .takeIf { ExitSafetyRule.test(it.getRelative(BlockFace.UP)) }
            ?.takeIf(type::use)

        if (anchorBlock == null) {
            world.clearExitAnchor(player)
            return false
        }

        player.teleportAsync(anchorBlock.getRelative(BlockFace.UP).location)
        return true
    }

    enum class Type(val key: Key) : Keyed {
        BED(Key.key(Vanilife.NAMESPACE, "bed")) {
            override fun use(block: Block): Boolean = block.blockData is Bed
        },
        RESPAWN_ANCHOR(Key.key(Vanilife.NAMESPACE, "respawn_anchor")) {
            override fun use(block: Block): Boolean {
                val blockData = block.blockData as? RespawnAnchor ?: return false
                if (blockData.charges <= 0) return false

                block.blockData = blockData.apply {
                    charges--
                }
                return true
            }
        },
        FORCE(Key.key(Vanilife.NAMESPACE, "force")) {
            override fun use(block: Block): Boolean = true
        };

        override fun key(): Key = key

        abstract fun use(block: Block): Boolean

        fun withPosition(position: BlockPosition): ExitAnchor = ExitAnchor(this, position)

        fun withPosition(x: Int, y: Int, z: Int): ExitAnchor = withPosition(Position.block(x, y, z))

        companion object {
            val BY_KEY: Map<Key, Type> = Type.entries.associateBy { it.key }

            fun byKey(key: Key): Type? = BY_KEY[key]
        }
    }
}
