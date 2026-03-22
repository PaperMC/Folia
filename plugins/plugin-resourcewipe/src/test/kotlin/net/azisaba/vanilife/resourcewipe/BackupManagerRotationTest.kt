package net.azisaba.vanilife.resourcewipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import java.io.File
import kotlin.io.path.createTempDirectory

class BackupManagerRotationTest : StringSpec({
    "rotation keeps only configured number of backups" {
        val temp = createTempDirectory().toFile()
        val world = File(temp, "world")
        world.mkdirs()
        val f = File(world, "a.txt")
        f.writeText("ok")

        val data = createTempDirectory().toFile()
        val bm = BackupManager(data)
        bm.enabled = true
        // create 5 backups
        repeat(5) {
            bm.createBackup(world, 3)
            Thread.sleep(10)
        }
        val files = data.resolve("backups").resolve(world.name).listFiles()!!
        // should keep only 3
        files.toList().shouldHaveSize(3)

        data.deleteRecursively()
        temp.deleteRecursively()
    }
})
