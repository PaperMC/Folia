import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    kotlin("jvm") version libs.versions.kotlin.get() apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.shadow) apply false
}

val kotlinx = libs.kotlinx
val koin = libs.koin
val tomlkt = libs.tomlkt
val mccoroutine = libs.mccoroutine
val packetevents = libs.packetevents
val entitylib = libs.entitylib
val packed = libs.packed

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "com.gradleup.shadow")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://maven.pvphub.me/tofaa")
        mavenLocal()
    }

    dependencies {
        compileOnly("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")
        compileOnly(packetevents)
        implementation(kotlinx.coroutines.core)
        implementation(koin.core)
        implementation(tomlkt)
        implementation(mccoroutine.folia.api)
        implementation(mccoroutine.folia.core)
        implementation(entitylib)
        implementation(packed)
        implementation(kotlin("stdlib"))
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    tasks.named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
    }
    tasks.named<Jar>("jar") {
        enabled = false
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            optIn.add("kotlin.uuid.ExperimentalUuidApi")
        }
    }
}

tasks.register("buildAllPluginJars") {
    group = "build"
    description = "Build shadow JARs for all plugin subprojects"
    dependsOn(subprojects.map { it.tasks.named("shadowJar") })
}
