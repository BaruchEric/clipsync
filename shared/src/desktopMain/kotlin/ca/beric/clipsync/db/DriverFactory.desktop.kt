package ca.beric.clipsync.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val dir = File(System.getProperty("user.home"), "Library/Application Support/clipsync")
        dir.mkdirs()
        val dbFile = File(dir, "history.db")
        val fresh = !dbFile.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        if (fresh) ClipsyncDb.Schema.create(driver)
        return driver
    }
}
