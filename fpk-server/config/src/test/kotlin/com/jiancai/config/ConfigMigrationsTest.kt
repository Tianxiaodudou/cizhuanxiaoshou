package com.jiancai.config

import com.jiancai.db.DbCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 迁移验收：号段归属、建表、重复迁移幂等。
 */
class ConfigMigrationsTest {

    @Test
    fun deployment_config_table_is_created_by_the_config_migration() {
        DbCore.testDatabase(ConfigMigrations.all).use { db ->
            assertEquals(ConfigMigrations.VERSION, db.migrator.currentVersion())
            assertTrue(db.migrator.pendingVersions().isEmpty())

            val tables = db.txRunner.run { tx ->
                tx.queryLong(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = 'deployment_config'",
                )
            }
            assertEquals(1L, tables)
        }
    }

    @Test
    fun migration_version_is_in_the_config_segment_and_idempotent() {
        assertTrue(ConfigMigrations.VERSION in 200..299, "S01 迁移号段为 200–299")

        DbCore.testDatabase(ConfigMigrations.all).use { db ->
            val report = db.migrator.migrate()
            assertTrue(report.applied.isEmpty(), "重复 migrate 不应重复执行")
            assertEquals(listOf(ConfigMigrations.VERSION), report.skipped)
        }
    }
}
