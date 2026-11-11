/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   35 行  类 GatewayMigrationsTest
 * 第   39 行  函数 db
 * 第   41 行  函数 clock
 * 第   45 行  函数 the_migration_creates_the_idempotency_table
 * 第   62 行  函数 re_running_the_migration_is_idempotent
 * 第   72 行  函数 a_response_snapshot_survives_a_round_trip
 * 第   91 行  函数 a_released_key_can_be_reused
 * 第  103 行  函数 an_expired_snapshot_is_not_replayed
 * 第  117 行  函数 writing_a_new_snapshot_purges_expired_rows
 * 第  135 行  函数 the_table_rejects_a_non_success_status
 */

package com.jiancai.gateway

import com.jiancai.db.DbCore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 持久化幂等台账（`gateway_idempotency`，迁移 800）：
 * 1. 迁移建表 + 版本推进 + 幂等；
 * 2. 快照往返（状态 / 正文 / Content-Type / 响应头，含中文）；
 * 3. 失败释放后同一个 key 可重用；
 * 4. TTL 过期不再重放，并在写入时顺带清理。
 */
class GatewayMigrationsTest {

    private val t0: Instant = Instant.parse("2026-11-11T01:00:00Z")

    private fun db(): DbCore = DbCore.testDatabase(GatewayMigrations.all)

    private fun clock(offset: Duration): Clock =
        Clock.fixed(t0.plus(offset), ZoneId.of("Asia/Shanghai"))

    @Test
    fun the_migration_creates_the_idempotency_table() {
        db().use { core ->
            assertEquals(GatewayMigrations.VERSION, core.migrator.currentVersion())
            val tables = core.txRunner.run { tx ->
                tx.connection.prepareStatement("SELECT name FROM sqlite_master WHERE type='table'").use { statement ->
                    statement.executeQuery().use { rows ->
                        val names = mutableListOf<String>()
                        while (rows.next()) names += rows.getString(1)
                        names
                    }
                }
            }
            assertTrue(tables.contains("gateway_idempotency"), "缺少幂等台账表：$tables")
        }
    }

    @Test
    fun re_running_the_migration_is_idempotent() {
        db().use { core ->
            val report = core.migrator.migrate()
            assertEquals(emptyList(), report.applied)
            assertEquals(listOf(GatewayMigrations.VERSION), report.skipped)
            assertEquals(emptyList(), core.migrator.drift())
        }
    }

    @Test
    fun a_response_snapshot_survives_a_round_trip() {
        db().use { core ->
            val store = SqliteIdempotencyStore(core.txRunner, clock(Duration.ZERO))
            val response = ApiResponse(
                status = 201,
                body = """{"order_no":"PS1","remark":"中文备注"}""",
                contentType = ApiResponse.APPLICATION_JSON,
                headers = mapOf("X-Trace-Id" to "t-1", "X-Idempotent-Replay" to "true"),
            )
            store.put("k-1", StoredResponse(fingerprint = "a".repeat(64), response = response))

            val restored = store.get("k-1")
            assertEquals(response, restored?.response, "重放必须逐字节一致")
            assertEquals("a".repeat(64), restored?.fingerprint)
            assertEquals(1, store.size())
        }
    }

    @Test
    fun a_released_key_can_be_reused() {
        db().use { core ->
            val store = SqliteIdempotencyStore(core.txRunner, clock(Duration.ZERO))
            store.put("k-1", StoredResponse("f".repeat(64), ApiResponse(201, "{}")))
            store.remove("k-1")

            assertNull(store.get("k-1"), "释放后不应再重放")
            assertEquals(0, store.size())
        }
    }

    @Test
    fun an_expired_snapshot_is_not_replayed() {
        db().use { core ->
            SqliteIdempotencyStore(core.txRunner, clock(Duration.ZERO))
                .put("k-1", StoredResponse("f".repeat(64), ApiResponse(201, "{}")))

            val later = SqliteIdempotencyStore(
                core.txRunner,
                clock(SqliteIdempotencyStore.DEFAULT_TTL + Duration.ofMinutes(1)),
            )
            assertNull(later.get("k-1"), "超过 TTL 的快照不得再重放")
        }
    }

    @Test
    fun writing_a_new_snapshot_purges_expired_rows() {
        db().use { core ->
            SqliteIdempotencyStore(core.txRunner, clock(Duration.ZERO))
                .put("old", StoredResponse("f".repeat(64), ApiResponse(201, "{}")))

            val later = SqliteIdempotencyStore(
                core.txRunner,
                clock(SqliteIdempotencyStore.DEFAULT_TTL + Duration.ofMinutes(1)),
            )
            later.put("new", StoredResponse("g".repeat(64), ApiResponse(201, "{}")))

            assertNull(later.get("old"))
            assertEquals("g".repeat(64), later.get("new")?.fingerprint)
            assertEquals(1, later.size(), "过期行应被顺带清理")
        }
    }

    @Test
    fun the_table_rejects_a_non_success_status() {
        db().use { core ->
            val failure = runCatching {
                core.txRunner.run { tx ->
                    tx.connection.prepareStatement(
                        """
                        INSERT INTO gateway_idempotency
                            (idempotency_key, fingerprint, status, content_type, headers_json, body, created_at, expires_at)
                        VALUES ('k', '${"a".repeat(64)}', 500, 'text/plain', '{}', '', 0, 1)
                        """.trimIndent(),
                    ).use { it.executeUpdate() }
                }
            }.exceptionOrNull()

            assertTrue(failure is java.sql.SQLException, "只允许缓存 2xx 响应：$failure")
        }
    }
}
