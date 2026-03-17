repositories {
    maven("https://maven.playpro.com/")
}

dependencies {
    compileOnly(libs.bettercommand)
    compileOnly(libs.betterhud.bukkit.api)
    compileOnly(libs.betterhud.standard.api)
    compileOnly(libs.coreprotect)
    compileOnly(libs.packed.core)
    compileOnly(libs.packed.resource)
    implementation(libs.mccoroutine.folia.api)
    implementation(libs.mccoroutine.folia.core)
}
