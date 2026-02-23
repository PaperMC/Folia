package net.azisaba.vanilife

import dev.eav.tomlkt.Toml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val defaultToml: Toml = Toml {
    ignoreUnknownKeys = true
}

internal fun Main.tomlConfig(toml: Toml = defaultToml): Config {
    val path = dataFolder.toPath().resolve("config.toml")
    if (!path.exists()) {
        val content = toml.encodeToString(Config.serializer(), Config())
        path.parent?.let { Files.createDirectories(it) }
        path.writeText(content, options = arrayOf(
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        ))
    }
    val pathContent = path.readText()
    return toml.decodeFromString(Config.serializer(), pathContent)
}

@Serializable
data class Config(
    val database: DatabaseConfig = DatabaseConfig(),
)

@Serializable
data class DatabaseConfig(
    val url: String = "jdbc:postgresql://localhost:5432/aetheria",
    @SerialName("username-env") val usernameEnv: String = "DATABASE_USERNAME",
    @SerialName("password-env") val passwordEnv: String = "DATABASE_PASSWORD",
    @SerialName("max-pool-size") val maxPoolSize: Int = 12,
)
