package net.azisaba.vanilife.resourcewipe

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter

class BackupManager(private val dataFolder: File) {
    private val backupsDir: Path = dataFolder.toPath().resolve("backups")
    var enabled: Boolean = true

    init {
        Files.createDirectories(backupsDir)
    }

    fun createBackup(worldFolder: File, retention: Int): Path? {
        if (!enabled) return null
        val ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-')
        val dest = backupsDir.resolve(worldFolder.name).resolve("$ts.zip")
        Files.createDirectories(dest.parent)
        ZipUtils.zipDirectory(worldFolder.toPath(), dest)
        rotateBackups(worldFolder.name, retention)
        return dest
    }

    fun rotateBackups(worldName: String, keep: Int) {
        val dir = backupsDir.resolve(worldName).toFile()
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        for (i in keep until files.size) {
            files[i].delete()
        }
    }
}
