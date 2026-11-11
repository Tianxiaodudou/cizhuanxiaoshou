/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   20 行  类 AuthMigrationsTest
 * 第   23 行  函数 auth_account_table_is_created_by_the_auth_migration
 * 第   39 行  函数 migration_version_is_in_the_auth_segment_and_idempotent
 * 第   50 行  函数 account_table_enforces_unique_usernames_and_known_roles
 */

package com.jiancai.auth

import com.jiancai.db.DbCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 迁移验收：号段归属、建表、重复迁移幂等。
 */
class AuthMigrationsTest {

    @Test
    fun auth_account_table_is_created_by_the_auth_migration() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            assertEquals(AuthMigrations.VERSION, db.migrator.currentVersion())
            assertTrue(db.migrator.pendingVersions().isEmpty())

            val tables = db.txRunner.run { tx ->
                tx.queryLong(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = 'auth_account'",
                )
            }
            assertEquals(1L, tables)
        }
    }

    @Test
    fun migration_version_is_in_the_auth_segment_and_idempotent() {
        assertTrue(AuthMigrations.VERSION in 210..219, "S02 迁移号段为 210–219（S01=200–209、S03=220–229）")

        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val report = db.migrator.migrate()
            assertTrue(report.applied.isEmpty(), "重复 migrate 不应重复执行")
            assertEquals(listOf(AuthMigrations.VERSION), report.skipped)
        }
    }

    @Test
    fun account_table_enforces_unique_usernames_and_known_roles() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            auth.bootstrapFirstAdmin("admin", "admin12345", "管理员")

            assertTrue(
                runCatching {
                    db.txRunner.run { tx ->
                        tx.exec(
                            "INSERT INTO auth_account " +
                                "(username, username_key, display_name, role, enabled, password_hash, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            "ADMIN", "admin", "重复账号", "ADMIN", 1,
                            "${Pbkdf2PasswordHasher.PREFIX}\$1000\$c2FsdA\$aGFzaA",
                            "2026-11-10T10:00:00+08:00", "2026-11-10T10:00:00+08:00",
                        )
                    }
                }.isFailure,
                "username_key 唯一约束必须生效",
            )

            assertTrue(
                runCatching {
                    db.txRunner.run { tx ->
                        tx.exec(
                            "INSERT INTO auth_account " +
                                "(username, username_key, display_name, role, enabled, password_hash, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            "other", "other", "未知角色", "SUPERUSER", 1,
                            "${Pbkdf2PasswordHasher.PREFIX}\$1000\$c2FsdA\$aGFzaA",
                            "2026-11-10T10:00:00+08:00", "2026-11-10T10:00:00+08:00",
                        )
                    }
                }.isFailure,
                "role 只允许 ADMIN / STAFF",
            )

            assertEquals(1L, db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM auth_account") })
        }
    }
}
