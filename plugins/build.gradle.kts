import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    java
    kotlin("jvm") version "2.3.0" apply false
    id("com.gradleup.shadow") version "9.3.1" apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.gradleup.shadow")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        compileOnly("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")
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
}

tasks.register("buildAllPluginJars") {
    group = "build"
    description = "Build shadow JARs for all plugin subprojects"
    dependsOn(subprojects.map { it.tasks.named("shadowJar") })
}
