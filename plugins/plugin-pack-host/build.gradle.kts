dependencies {
    compileOnly(project(":plugins:plugin-cooking"))
    compileOnly(project(":plugins:plugin-fishing"))
    compileOnly(project(":plugins:plugin-forestry"))
    compileOnly(project(":plugins:plugin-islands"))
    compileOnly(project(":plugins:plugin-mining"))
    compileOnly(project(":plugins:plugin-npc"))
    compileOnly(libs.packed.core)
    compileOnly(libs.packed.resource)
    compileOnly(libs.packed.server)
}
