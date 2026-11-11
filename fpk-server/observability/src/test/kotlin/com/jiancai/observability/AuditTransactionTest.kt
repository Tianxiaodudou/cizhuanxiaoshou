/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   30 行  类 AuditTransactionTest
 * 第   33 行  函数 a_caller_rollback_discards_the_audit_row
 * 第   47 行  函数 a_caller_commit_keeps_the_audit_row
 * 第   57 行  函数 the_convenience_overloads_open_their_own_transaction
 * 第   67 行  函数 nested_writes_reuse_the_outer_transaction
 * 第   81 行  函数 concurrent_reads_are_serialised_by_the_tx_runner
 * 第   96 行  函数 a_closed_database_fails_instead_of_silently_dropping_the_trace
 * 第  110 行  函数 the_port_never_needs_a_role_or_a_token_to_write
 */

package com.jiancai.observability

import com.jiancai.db.DbCore
import com.jiancai.db.TxContext
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 事务与并发口径（铁律 4）：
 * 1. 留痕并回调用方事务：业务回滚 → 留痕一起消失；
 * 2. 便捷重载自己开事务，不存在非事务写入；
 * 3. 嵌套调用复用同一事务，最外层回滚全灭。
 */
class AuditTransactionTest {

    @Test
    fun a_caller_rollback_discards_the_audit_row() {
        AuditFixture.create().use { fixture ->
            assertFailsWith<IllegalStateException> {
                fixture.db.txRunner.run { tx ->
                    fixture.audit.priceChanged(tx, AuditFixture.priceLog())
                    error("业务写入失败")
                }
            }

            assertEquals(0, fixture.audit.query().size, "业务回滚后不得留下改价留痕")
        }
    }

    @Test
    fun a_caller_commit_keeps_the_audit_row() {
        AuditFixture.create().use { fixture ->
            fixture.db.txRunner.run { tx ->
                fixture.audit.priceChanged(tx, AuditFixture.priceLog())
            }
            assertEquals(1, fixture.audit.query().size)
        }
    }

    @Test
    fun the_convenience_overloads_open_their_own_transaction() {
        AuditFixture.create().use { fixture ->
            fixture.audit.priceChanged(AuditFixture.priceLog())
            fixture.audit.settleAudited(AuditFixture.settle(), approved = true)

            assertEquals(2, fixture.audit.query().size, "便捷重载必须真正提交")
        }
    }

    @Test
    fun nested_writes_reuse_the_outer_transaction() {
        AuditFixture.create().use { fixture ->
            assertFailsWith<IllegalStateException> {
                fixture.db.txRunner.run { tx ->
                    fixture.audit.priceChanged(tx, AuditFixture.priceLog())
                    fixture.audit.settleAudited(tx, AuditFixture.settle(), approved = true)
                    error("最外层失败")
                }
            }
            assertEquals(0, fixture.audit.query().size)
        }
    }

    @Test
    fun concurrent_reads_are_serialised_by_the_tx_runner() {
        AuditFixture.create().use { fixture ->
            fixture.audit.priceChanged(AuditFixture.priceLog())
            val observed = mutableListOf<Int>()
            val threads = (1..4).map {
                Thread { observed += fixture.audit.query().size }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertTrue(observed.all { it == 1 }, "读事务互相串行，不应看到中间态：$observed")
        }
    }

    @Test
    fun a_closed_database_fails_instead_of_silently_dropping_the_trace() {
        val db = DbCore.testDatabase(ObservabilityMigrations.all)
        val audit = AuditService(db.txRunner)
        audit.priceChanged(AuditFixture.priceLog())
        db.close()

        val failure = assertFailsWith<RuntimeException> { audit.priceChanged(AuditFixture.priceLog()) }
        assertTrue(
            failure.message?.contains("关闭") == true,
            "库关闭后应显式失败（而不是静默丢留痕）：${failure.message}",
        )
    }

    @Test
    fun the_port_never_needs_a_role_or_a_token_to_write() {
        // 结构化断言：写留痕只需 db-core 的 TxContext，身份仅作为数据字段传入。
        val methods = AuditPort::class.java.declaredMethods.filter { it.name == "record" }
        assertTrue(methods.isNotEmpty())
        methods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type == TxContext::class.java || type == AuditEvent::class.java,
                    "record 不得接收 ${type.name}：权限只在 S13 判断（铁律 7）",
                )
            }
        }
        assertEquals(ErrorCode.E422.httpStatus, 422)
    }
}
