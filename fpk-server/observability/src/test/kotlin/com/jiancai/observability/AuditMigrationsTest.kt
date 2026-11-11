/*
 * @file fpk-server/observability/src/test/kotlin/com/jiancai/observability/AuditMigrationsTest.kt
 * @role 迁移与库层约束（版本 700）
 * @exports AuditMigrationsTest
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 写 audit_log（INSERT / UPDATE / DELETE）
 */

package com.jiancai.observability

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 迁移与库层约束（版本 700）：
 * 1. 建表 + 版本推进；
 * 2. 只追加台账的库层约束（动作白名单 / 操作者 / 目标非空 / 明细非空）；
 * 3. 重复迁移幂等（skipped，不重复建表）。
 */
class AuditMigrationsTest {

    @Test
    fun the_migration_creates_the_audit_table_and_advances_the_version() {
        AuditFixture.create().use { fixture ->
            assertEquals(ObservabilityMigrations.VERSION, fixture.db.migrator.currentVersion())
            val tables = tables(fixture)
            assertTrue(tables.contains("audit_log"), "缺少 audit_log 表：$tables")
        }
    }

    @Test
    fun re_running_the_migration_is_idempotent() {
        AuditFixture.create().use { fixture ->
            val report = fixture.db.migrator.migrate()
            assertEquals(emptyList(), report.applied)
            assertEquals(listOf(ObservabilityMigrations.VERSION), report.skipped)
            assertEquals(emptyList(), fixture.db.migrator.drift())
        }
    }

    @Test
    fun the_table_rejects_an_unknown_action() {
        AuditFixture.create().use { fixture ->
            val failure = assertFailsWith<SQLException> {
                insertRaw(fixture, action = "ORDER_DELETE")
            }
            assertTrue(failure.message!!.contains("CHECK"), "应为库层 CHECK 拒绝：${failure.message}")
        }
    }

    @Test
    fun the_table_rejects_a_non_positive_operator() {
        AuditFixture.create().use { fixture ->
            assertFailsWith<SQLException> { insertRaw(fixture, operatorId = 0) }
        }
    }

    @Test
    fun the_table_rejects_a_blank_target() {
        AuditFixture.create().use { fixture ->
            assertFailsWith<SQLException> { insertRaw(fixture, targetNo = "   ") }
        }
    }

    @Test
    fun the_table_requires_a_non_empty_details_payload() {
        AuditFixture.create().use { fixture ->
            assertFailsWith<SQLException> { insertRaw(fixture, detailsJson = "") }
        }
    }

    @Test
    fun the_module_owns_exactly_one_table() {
        AuditFixture.create().use { fixture ->
            val owned = ObservabilityMigrations.all.flatMap { migration ->
                Regex("""CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)""", RegexOption.IGNORE_CASE)
                    .findAll(migration.statements.joinToString("\n"))
                    .map { it.groupValues[1] }
                    .toList()
            }
            assertEquals(listOf("audit_log"), owned, "S14 只拥有 audit_log 一张表（铁律 10）")
            assertTrue(tables(fixture).contains("audit_log"))
        }
    }

    private fun tables(fixture: AuditFixture): List<String> =
        fixture.db.txRunner.run { tx ->
            tx.connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    val names = mutableListOf<String>()
                    while (rows.next()) names += rows.getString(1)
                    names
                }
            }
        }

    private fun insertRaw(
        fixture: AuditFixture,
        action: String = "PRICE_CHANGE",
        targetNo: String = "1/2/-",
        operatorId: Long = 1,
        detailsJson: String = "{}",
    ) {
        fixture.db.txRunner.run { tx ->
            tx.connection.prepareStatement(
                """
                INSERT INTO audit_log (action, target_type, target_no, operator_id, details_json, created_at)
                VALUES (?, 'price', ?, ?, ?, '2026-11-11T09:00:00+08:00')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, action)
                statement.setString(2, targetNo)
                statement.setLong(3, operatorId)
                statement.setString(4, detailsJson)
                statement.executeUpdate()
            }
        }
    }
}
