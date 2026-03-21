dependencies {
    compileOnly(libs.packed.core)
    compileOnly(libs.packed.resource)
    compileOnly(project(":plugins:plugin-islands"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikaricp)
    implementation(libs.koin.core)
    implementation(libs.mccoroutine.folia.api)
    implementation(libs.mccoroutine.folia.core)
    implementation(libs.postgresql)
    implementation(libs.tomlkt)
}
