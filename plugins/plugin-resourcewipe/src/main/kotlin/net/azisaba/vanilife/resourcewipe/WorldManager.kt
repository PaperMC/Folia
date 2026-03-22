package net.azisaba.vanilife.resourcewipe

import org.bukkit.Bukkit
import org.bukkit.World
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class WorldManager(private val pluginDataFolder: File) {
    fun resetWorldByTemplate(worldName: String, templatePath: Path) {
        val world = Bukkit.getWorld(worldName)
        if (world != null) {
            // Teleport players out
            world.players.forEach { p ->
                p.teleport(Bukkit.getWorlds().first().spawnLocation)
            }
            // Unload world
            Bukkit.unloadWorld(world, false)
        }

        val worldFolder = File(Bukkit.getWorldContainer(), worldName)
        if (worldFolder.exists()) {
            val moved = pluginDataFolder.toPath().resolve("old_worlds").resolve(worldName + "-" + System.currentTimeMillis())
            Files.createDirectories(moved.parent)
            Files.move(worldFolder.toPath(), moved, StandardCopyOption.ATOMIC_MOVE)
        }

        // Copy template into place
        Files.createDirectories(worldFolder.toPath())
        templatePath.toFile().copyRecursively(worldFolder)

        // Load world
        Bukkit.createWorld(org.bukkit.WorldCreator(worldName))
    }
}
