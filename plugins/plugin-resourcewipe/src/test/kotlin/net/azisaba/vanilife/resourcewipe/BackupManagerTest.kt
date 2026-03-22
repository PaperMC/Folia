package net.azisaba.vanilife.resourcewipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File
import kotlin.io.path.createTempDirectory

class BackupManagerTest : StringSpec({
    "createBackup returns a path when enabled" {
        val temp = createTempDirectory().toFile()
        val world = File(temp, "world")
        world.mkdirs()
        val f = File(world, "a.txt")
        f.writeText("ok")

        val data = createTempDirectory().toFile()
        val bm = BackupManager(data)
        bm.enabled = true
        val p = bm.createBackup(world, 3)
        p.shouldNotBeNull()
        val files = data.resolve("backups").resolve(world.name).listFiles()!!
        files.size.shouldBeGreaterThan(0)

        data.deleteRecursively()
        temp.deleteRecursively()
    }

    "createBackup returns null when disabled" {
        val temp = createTempDirectory().toFile()
        val world = File(temp, "world2")
        world.mkdirs()
        val f = File(world, "a.txt")
        f.writeText("ok")

        val data = createTempDirectory().toFile()
        val bm = BackupManager(data)
        bm.enabled = false
        val p = bm.createBackup(world, 3)
        p == null

        data.deleteRecursively()
        temp.deleteRecursively()
    }
})
