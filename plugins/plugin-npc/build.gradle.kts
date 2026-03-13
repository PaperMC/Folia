dependencies {
    compileOnly(project(":plugins:plugin-cooking"))
    compileOnly(project(":plugins:plugin-fishing"))
    compileOnly(project(":plugins:plugin-islands"))
    compileOnly(libs.bettermodel.bukkit.api)
    implementation(libs.koin.core)
    implementation(libs.mccoroutine.folia.api)
    implementation(libs.mccoroutine.folia.core)
}
