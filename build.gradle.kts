import io.papermc.paperweight.tasks.RebuildGitPatches
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java // TODO java launcher tasks
    alias(libs.plugins.kotlin)
    alias(libs.plugins.paperweight.patcher)
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.shadow) apply false
}

paperweight {
    upstreams.paper {
        ref = providers.gradleProperty("paperRef")

        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("folia-server/build.gradle.kts")
            patchFile = file("folia-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("folia-api/build.gradle.kts")
            patchFile = file("folia-api/build.gradle.kts.patch")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("folia-api/paper-patches")
            outputDir = file("paper-api")
        }
    }
}

val kotlinx = libs.kotlinx
val koin = libs.koin
val bettermodel = libs.bettermodel
val entitylib = libs.entitylib
val mccoroutine = libs.mccoroutine
val packed = libs.packed
val packetevents = libs.packetevents
val exposed = libs.exposed
val hikaricp = libs.hikaricp
val postgresql = libs.postgresql
val jnoise = libs.jnoise
val tomlkt = libs.tomlkt

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
        maven("https://repo.azisaba.net/repository/maven-public/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://maven.pvphub.me/tofaa")
    }

    dependencies {
        compileOnly(kotlin("stdlib"))
        compileOnly(kotlinx.coroutines.core)
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile>().configureEach  {
        options.encoding = Charsets.UTF_8.name()
        options.release = 21
        options.isFork = true
    }
    tasks.withType<Javadoc>().configureEach  {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach  {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test>().configureEach  {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://repo.papermc.io/repository/maven-snapshots/") {
                name = "paperSnapshots"
                credentials(PasswordCredentials::class)
            }
        }
    }
}

allprojects {
    tasks.withType<RebuildGitPatches>().configureEach {
        filterPatches = false
    }
}

project(":folia-server") {
    dependencies {
        implementation(kotlin("stdlib"))
        implementation(kotlinx.coroutines.core)
        implementation(jnoise.pipeline)
    }
}

configure(subprojects.filter { it.path.startsWith(":plugins:") }) {
    apply(plugin = "com.gradleup.shadow")

    dependencies {
        compileOnly(project(":folia-api"))
    }

    tasks.named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
    }
}

tasks.register("printMinecraftVersion") {
    doLast {
        println(providers.gradleProperty("mcVersion").get().trim())
    }
}

tasks.register("printPaperVersion") {
    doLast {
        println(project.version)
    }
}

gradle.projectsEvaluated {
    val pluginProjects = subprojects.filter { it.path.startsWith(":plugins:") }
    val pluginShadowJarTasks = pluginProjects.map { it.tasks.named<ShadowJar>("shadowJar") }

    listOf("runServer", "runDevServer").forEach { taskName ->
        (findProject(":folia-server")?.tasks?.findByName(taskName) as? JavaExec)?.apply {
            dependsOn(pluginShadowJarTasks)
            doFirst {
                pluginShadowJarTasks.forEach { shadowJarTask ->
                    args("--add-plugin", shadowJarTask.get().archiveFile.get().asFile.absolutePath)
                }
            }
        }
    }
}
