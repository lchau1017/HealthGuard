package com.medguard.shared.data

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform factory for the SQLDelight driver backing [com.medguard.shared.db.MedGuardDb].
 * Every actual must enable foreign-key enforcement so ON DELETE CASCADE works.
 */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}
