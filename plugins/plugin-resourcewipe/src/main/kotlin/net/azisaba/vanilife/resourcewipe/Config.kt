package net.azisaba.vanilife.resourcewipe

import org.bukkit.configuration.file.FileConfiguration

data class ResourceWorldConfig(
    val name: String,
    val templatePath: String,
    val wipeSchedule: String?,
    val wipeIntervalMinutes: Long?,
    val backup: Boolean,
    val retention: Int,
    val preserveInventories: Boolean,
    val teleportTarget: String?
)

class PluginConfig(private val cfg: FileConfiguration) {
    val globalBackupEnabled: Boolean
        get() = cfg.getBoolean("backup.enabled", true)

    val globalDefaultRetention: Int
        get() = cfg.getInt("backup.defaultRetention", 7)

    fun getResourceWorlds(): List<ResourceWorldConfig> {
        val list = cfg.getMapList("resourceWorlds")
        return list.map { m ->
            val name = m["name"] as? String ?: "resource_world"
            val templatePath = m["templatePath"] as? String ?: "templates/$name"
            val wipeSchedule = m["wipeSchedule"] as? String
            val wipeIntervalMinutes = (m["wipeIntervalMinutes"] as? Number)?.toLong()
            val backup = (m["backup"] as? Boolean) ?: true
            val retention = (m["retention"] as? Number)?.toInt() ?: globalDefaultRetention
            val preserveInventories = (m["preserveInventories"] as? Boolean) ?: false
            val teleportTarget = m["teleportTarget"] as? String
            ResourceWorldConfig(name, templatePath, wipeSchedule, wipeIntervalMinutes, backup, retention, preserveInventories, teleportTarget)
        }
    }
}
