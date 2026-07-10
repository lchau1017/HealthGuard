package com.healthguard.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.healthguard.data.db.HealthGuardDb
import java.util.Properties

/**
 * In-memory JDBC driver, used by JVM unit tests. Foreign keys must be enabled
 * via connection properties (SQLite defaults them off) so ON DELETE CASCADE
 * behaves like production.
 */
actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver = JdbcSqliteDriver(
            JdbcSqliteDriver.IN_MEMORY,
            Properties().apply { put("foreign_keys", "true") },
        )
        HealthGuardDb.Schema.create(driver)
        return driver
    }
}
