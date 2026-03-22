package net.azisaba.vanilife.resourcewipe

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class ResourceWipeCommand(private val plugin: ResourceWipePlugin) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("Usage: /resourcewipe <status|force|backup|pause|resume|backup-toggle> [world]")
            return true
        }
        when (args[0].lowercase()) {
            "status" -> {
                sender.sendMessage("ResourceWipe: status command - not implemented yet")
            }
            "force" -> {
                val world = args.getOrNull(1) ?: run {
                    sender.sendMessage("Specify a world")
                    return true
                }
                plugin.logger.info("Force wipe requested for $world by ${sender.name}")
                sender.sendMessage("Requested force wipe for $world")
            }
            "backup" -> {
                val world = args.getOrNull(1) ?: run {
                    sender.sendMessage("Specify a world to backup")
                    return true
                }
                val worldFolder = java.io.File(org.bukkit.Bukkit.getWorldContainer(), world)
                val cfg = plugin.savedConfig
                val rwCfg = cfg.getResourceWorlds().find { it.name == world }
                val retention = rwCfg?.retention ?: cfg.globalDefaultRetention
                val path = plugin.backupManager.createBackup(worldFolder, retention)
                if (path != null) sender.sendMessage("Backup created: $path") else sender.sendMessage("Backup system is disabled")
            }
            "backup-toggle" -> {
                // toggle global backup enabled
                val newVal = !plugin.backupManager.enabled
                plugin.backupManager.enabled = newVal
                sender.sendMessage("Backup system enabled = $newVal")
                plugin.logger.info("Backup system toggled to $newVal by ${sender.name}")
            }
            else -> sender.sendMessage("Unknown subcommand")
        }
        return true
    }
}
