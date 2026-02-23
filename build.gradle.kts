import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import io.papermc.paperweight.tasks.RebuildGitPatches

plugins {
    java // TODO java launcher tasks
    alias(libs.plugins.paperweight.patcher)
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

val jnoise = libs.jnoise

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
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        "implementation"(jnoise.pipeline)
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

val runWorkDir = providers.gradleProperty("paper.runWorkDir").orElse("run")

val preparePlugins = tasks.register<Sync>("preparePlugins") {
    group = "runs"
    description = "Build and install plugins into 'run/plugins' (prefixed with module-)"

    if (gradle.includedBuilds.any { it.name == "plugins" }) {
        dependsOn(gradle.includedBuild("plugins").task(":buildAllPluginJars"))
    }

    preserve {
        include("*.jar")
        exclude("module-*.jar")
    }

    from(layout.projectDirectory.dir("plugins")) {
        include("**/build/libs/*.jar")
        exclude("**/*-sources.jar", "**/*-javadoc.jar")

        eachFile {
            val newName = "module-$name"
            relativePath = RelativePath(true, newName)
        }

        includeEmptyDirs = false
    }

    into(runWorkDir.map { layout.projectDirectory.dir("$it/plugins") })
}

gradle.projectsEvaluated {
    findProject(":folia-server")
        ?.tasks
        ?.findByName("runServer")
        ?.dependsOn(preparePlugins)

    findProject(":folia-server")
        ?.tasks
        ?.findByName("runDevServer")
        ?.dependsOn(preparePlugins)
}
