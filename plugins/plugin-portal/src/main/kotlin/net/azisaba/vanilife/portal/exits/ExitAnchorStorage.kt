package net.azisaba.vanilife.portal.exits

import io.papermc.paper.math.BlockPosition
import net.azisaba.vanilife.Vanilife
import net.kyori.adventure.key.Key
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataHolder
import org.bukkit.persistence.PersistentDataType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

internal class ExitAnchorStorage(private val holder: PersistentDataHolder) {
    private val rootContainer: PersistentDataContainer
        get() = holder.persistentDataContainer.get(CONTAINER_KEY, PersistentDataType.TAG_CONTAINER)
            ?: holder.persistentDataContainer.adapterContext.newPersistentDataContainer()

    fun getExitAnchor(uuid: UUID): ExitAnchor? {
        val anchorContainer = rootContainer.get(playerKey(uuid), PersistentDataType.TAG_CONTAINER) ?: return null

        val type = anchorContainer.get(TYPE_KEY, PersistentDataType.STRING)?.let {
            ExitAnchor.Type.byKey(Key.key(it))
        }
        val x = anchorContainer.get(POS_X_KEY, PersistentDataType.INTEGER)
        val y = anchorContainer.get(POS_Y_KEY, PersistentDataType.INTEGER)
        val z = anchorContainer.get(POS_Z_KEY, PersistentDataType.INTEGER)

        if (type == null || x == null || y == null || z == null) {
            logger.warn("Exit Anchor for '$uuid' in is incomplete (type=$type, x=$x, y=$y, z=$z); clearing it.")
            clearExitAnchor(uuid)
            return null
        }

        return type.withPosition(x, y, z)
    }

    fun setExitAnchor(uuid: UUID, type: ExitAnchor.Type, position: BlockPosition) {
        val anchorContainer = holder.persistentDataContainer.adapterContext.newPersistentDataContainer().apply {
            set(TYPE_KEY, PersistentDataType.STRING, type.key().toString())
            set(POS_X_KEY, PersistentDataType.INTEGER, position.blockX())
            set(POS_Y_KEY, PersistentDataType.INTEGER, position.blockY())
            set(POS_Z_KEY, PersistentDataType.INTEGER, position.blockZ())
        }

        val newRootContainer = rootContainer.apply {
            set(playerKey(uuid), PersistentDataType.TAG_CONTAINER, anchorContainer)
        }

        holder.persistentDataContainer.set(CONTAINER_KEY, PersistentDataType.TAG_CONTAINER, newRootContainer)
    }

    fun clearExitAnchor(uuid: UUID) {
        val newRootContainer = rootContainer.apply {
            remove(playerKey(uuid))
        }

        if (!newRootContainer.isEmpty) {
            holder.persistentDataContainer.set(CONTAINER_KEY, PersistentDataType.TAG_CONTAINER, newRootContainer)
        } else {
            holder.persistentDataContainer.remove(CONTAINER_KEY)
        }
    }

    private companion object {
        val CONTAINER_KEY: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "exit_anchors")
        val TYPE_KEY: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "type")
        val POS_X_KEY: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "x")
        val POS_Y_KEY: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "y")
        val POS_Z_KEY: NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, "z")

        val logger: Logger = LoggerFactory.getLogger(ExitAnchorStorage::class.java)

        fun playerKey(uuid: UUID): NamespacedKey = NamespacedKey(Vanilife.NAMESPACE, uuid.toString())
    }
}
