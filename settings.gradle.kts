pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "folia"

include("folia-api")
include("folia-server")
if (gradle.startParameter.taskNames.any { it.substringAfterLast(':') in setOf("runServer", "runDevServer") }) {
    includeBuild("plugins")
}
