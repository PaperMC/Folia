package net.azisaba.vanilife.resourcewipe

import org.bukkit.plugin.java.JavaPlugin

class ResourceWipePlugin : JavaPlugin() {
    lateinit var backupManager: BackupManager
    lateinit var worldManager: WorldManager
    lateinit var scheduler: Scheduler
    lateinit var savedConfig: PluginConfig

    override fun onEnable() {
        logger.info("ResourceWipePlugin enabling")
        saveDefaultConfig()
        val cfg = PluginConfig(config)
        this.savedConfig = cfg
        backupManager = BackupManager(dataFolder)
        backupManager.enabled = cfg.globalBackupEnabled
        worldManager = WorldManager(dataFolder)
        scheduler = Scheduler(this)

        // Register command
        getCommand("resourcewipe")?.setExecutor(ResourceWipeCommand(this))

        // Schedule simple periodic task for each configured world if interval provided
        cfg.getResourceWorlds().forEach { rw ->
            val minutes = rw.wipeIntervalMinutes ?: 0L
            if (minutes > 0L) {
                scheduler.scheduleRepeatingMinutes(minutes) {
                    logger.info("Scheduled wipe would run for ${rw.name}")
                }
            }
        }
    }

    override fun onDisable() {
        logger.info("ResourceWipePlugin disabling")
    }
}
