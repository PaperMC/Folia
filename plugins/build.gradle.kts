plugins {
    java
    kotlin("jvm") version "2.3.0" apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    dependencies {
        "compileOnly"("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")
        "implementation"(kotlin("stdlib"))
    }
}
