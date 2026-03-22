package net.azisaba.vanilife.resourcewipe

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipUtils {
    fun zipDirectory(sourceDirPath: Path, zipFilePath: Path) {
        ZipOutputStream(FileOutputStream(zipFilePath.toFile())).use { zs ->
            val sourcePath = sourceDirPath.toFile()
            zipFile(sourcePath, sourcePath, zs)
        }
    }

    private fun zipFile(rootDir: File, source: File, zs: ZipOutputStream) {
        if (source.isDirectory) {
            source.listFiles()?.forEach { child ->
                zipFile(rootDir, child, zs)
            }
        } else {
            val name = rootDir.toPath().relativize(source.toPath()).toString().replace('\\', '/')
            val zipEntry = ZipEntry(name)
            zs.putNextEntry(zipEntry)
            BufferedInputStream(FileInputStream(source)).use { origin ->
                origin.copyTo(zs)
            }
            zs.closeEntry()
        }
    }
}
