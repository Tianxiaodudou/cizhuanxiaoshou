package com.jiancai.serial

import com.jiancai.db.DbCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 迁移验收：号段归属、建表、重复迁移幂等。
 */
class SerialMigrationsTest {

    @Test
    fun serial_tables_are_created_by_the_serial_migration() {
        DbCore.testDatabase(SerialMigrations.all).use { db ->
            assertEquals(SerialMigrations.VERSION, db.migrator.currentVersion())
            assertTrue(db.migrator.pendingVersions().isEmpty())

            val tables = db.txRunner.run { tx ->
                tx.queryLong(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name IN ('serial_counter', 'serial_number')",
                )
            }
            assertEquals(2L, tables)
        }
    }

    @Test
    fun migration_version_is_in_the_serial_segment_and_idempotent() {
        assertTrue(SerialMigrations.VERSION in 100..199, "S07 迁移号段为 100–199")

        DbCore.testDatabase(SerialMigrations.all).use { db ->
            val report = db.migrator.migrate()
            assertTrue(report.applied.isEmpty(), "重复 migrate 不应重复执行")
            assertEquals(listOf(SerialMigrations.VERSION), report.skipped)
        }
    }
}
