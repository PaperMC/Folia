pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://wav.jfrog.io/artifactory/repo/")
    }
}

rootProject.name = "ForkTest"

include("ForkTest-API", "ForkTest-Server")
