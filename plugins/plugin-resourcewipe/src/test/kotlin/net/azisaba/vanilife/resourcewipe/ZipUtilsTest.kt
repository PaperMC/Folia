package net.azisaba.vanilife.resourcewipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.file.shouldExist
import java.io.File
import kotlin.io.path.createTempDirectory

class ZipUtilsTest : StringSpec({
    "zipDirectory creates a zip file containing files" {
        val tempDir = createTempDirectory().toFile()
        val sub = File(tempDir, "sub")
        sub.mkdirs()
        val f = File(sub, "hello.txt")
        f.writeText("hello world")

        val zipOut = File(tempDir.parentFile, "testout.zip")
        ZipUtils.zipDirectory(tempDir.toPath(), zipOut.toPath())

        zipOut.shouldExist()
        zipOut.delete()
        tempDir.deleteRecursively()
    }

    "zipDirectory works for empty directories" {
        val tempDir = createTempDirectory().toFile()
        // leave empty
        val zipOut = File(tempDir.parentFile, "empty.zip")
        ZipUtils.zipDirectory(tempDir.toPath(), zipOut.toPath())
        zipOut.shouldExist()
        zipOut.delete()
        tempDir.deleteRecursively()
    }
})
