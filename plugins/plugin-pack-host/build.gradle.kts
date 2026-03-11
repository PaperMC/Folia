dependencies {
    compileOnly(project(":plugins:plugin-cooking"))
    compileOnly(project(":plugins:plugin-fishing"))
    compileOnly(project(":plugins:plugin-islands"))
    compileOnly(libs.packed.core)
    compileOnly(libs.packed.resource)
    compileOnly(libs.packed.server)
}
