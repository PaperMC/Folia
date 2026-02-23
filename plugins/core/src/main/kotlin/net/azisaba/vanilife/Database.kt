package net.azisaba.vanilife

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.azisaba.vanilife.islands.ExposedIslandRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

internal fun Main.setupDatabase(databaseConfig: DatabaseConfig): Database {
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = databaseConfig.url
        username = System.getenv(databaseConfig.usernameEnv)
        password = System.getenv(databaseConfig.passwordEnv)
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = databaseConfig.maxPoolSize
    }

    val dataSource = HikariDataSource(hikariConfig)

    return Database.connect(dataSource)
}

internal fun Main.setupTables(database: Database) = transaction(database) {
    SchemaUtils.create(
        ExposedIslandRepository.IslandsTable,
    )
}
