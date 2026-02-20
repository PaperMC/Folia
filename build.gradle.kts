import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import io.papermc.paperweight.tasks.RebuildGitPatches

plugins {
    java // TODO java launcher tasks
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.19"
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

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
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

val preparePlugins = tasks.register<Copy>("preparePlugins") {
    group = "runs"
    description = "Build and install plugins into 'run/plugins' (prefixed with module-)"

    dependsOn(gradle.includedBuild("plugins").task(":buildAllPluginJars"))

    val runDir = providers.gradleProperty("paper.runWorkDir").orElse("run")
    val pluginsDirProvider = runDir.map { layout.projectDirectory.dir("plugins/$it").asFile }

    doFirst {
        val pluginsDir = pluginsDirProvider.get()
        if (!pluginsDir.exists()) pluginsDir.mkdir()

        pluginsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("module-") && it.extension == "jar" }
            ?.forEach { it.delete() }
    }

    from(fileTree("plugins") {
        include("**/build/libs/*.jar")
        exclude("**/*-sources.jar", "**/*-javadoc.jar")
    }) {
        eachFile {
            relativePath = RelativePath(true, name)
        }
        includeEmptyDirs = false
    }

    into(layout.projectDirectory.dir("run/plugins"))
}


gradle.projectsEvaluated {
    project(":folia-server").tasks.named("runServer") {
        dependsOn(preparePlugins)
    }

    project(":folia-server").tasks.named("runDevServer") {
        dependsOn(preparePlugins)
    }
}
