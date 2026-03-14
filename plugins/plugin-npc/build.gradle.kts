dependencies {
    compileOnly(project(":plugins:plugin-cooking"))
    compileOnly(project(":plugins:plugin-fishing"))
    compileOnly(project(":plugins:plugin-forestry"))
    compileOnly(project(":plugins:plugin-islands"))
    compileOnly(libs.bettermodel.bukkit.api)
    compileOnly(libs.packed.core)
    compileOnly(libs.packed.resource)
    implementation(libs.koin.core)
    implementation(libs.mccoroutine.folia.api)
    implementation(libs.mccoroutine.folia.core)
}
