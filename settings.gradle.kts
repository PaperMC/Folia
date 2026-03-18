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

rootProject.name = "vanilife"

include("folia-api")
include("folia-server")
include("plugins:plugin-cooking")
include("plugins:plugin-farming")
include("plugins:plugin-fishing")
include("plugins:plugin-forestry")
include("plugins:plugin-islands")
include("plugins:plugin-mining")
include("plugins:plugin-npc")
include("plugins:plugin-pack-host")
include("plugins:plugin-portal")
include("plugins:plugin-tool-swap")
