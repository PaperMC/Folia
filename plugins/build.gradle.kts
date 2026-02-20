plugins {
    java
    kotlin("jvm") version "2.3.0" apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        "compileOnly"("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")
        "implementation"(kotlin("stdlib"))
    }
}

tasks.register("buildAllPluginJars") {
    group = "build"
    description = "Build JARs for all plugin subprojects"

    dependsOn(subprojects.map { it.tasks.named<Jar>("jar") })
}
