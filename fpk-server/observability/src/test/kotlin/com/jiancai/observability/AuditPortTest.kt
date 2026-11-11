/*
 * @file fpk-server/observability/src/test/kotlin/com/jiancai/observability/AuditPortTest.kt
 * @role 验收「改价 / 审核有留痕」的正面用例 + 留痕模型的负向用例。
 * @exports AuditPortTest
 * @depends shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.observability

import com.jiancai.shared.enums.SettleStatus
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验收「改价 / 审核有留痕」的正面用例 + 留痕模型的负向用例。
 *
 * 断言口径：留痕只记录已发生的事实（时间 / 操作者由服务端产出），且只追加、可查询。
 */
class AuditPortTest {

    @Test
    fun a_price_change_is_traced_with_the_old_and_the_new_price() {
        AuditFixture.create().use { fixture ->
            val record = fixture.audit.priceChanged(AuditFixture.priceLog())

            assertEquals(AuditAction.PRICE_CHANGE, record.action)
            assertEquals("price", record.targetType)
            assertEquals("11/22/A1", record.targetNo)
            assertEquals(7, record.operatorId)
            assertEquals("改价 100.00 → 120.50", record.summary)
            assertEquals("100.00", record.details["old_price"])
            assertEquals("120.50", record.details["new_price"])
            assertEquals("客户议价", record.details["reason"])
            assertEquals("2026-11-11T09:00:00+08:00", record.createdAt)
            assertTrue(record.id > 0)
        }
    }

    @Test
    fun a_settlement_audit_is_traced_with_the_audited_amount() {
        AuditFixture.create().use { fixture ->
            val record = fixture.audit.settleAudited(
                settle = AuditFixture.settle(),
                approved = true,
                remark = "已核对现金",
            )

            assertEquals(AuditAction.SETTLE_AUDIT, record.action)
            assertEquals("settlement", record.targetType)
            assertEquals("JS202611110001", record.targetNo)
            assertEquals(9, record.operatorId)
            assertEquals("结算审核通过", record.summary)
            assertEquals("500.00", record.details["amount"])
            assertEquals("APPROVED", record.details["status"])
            assertEquals("已核对现金", record.details["audit_remark"])
        }
    }

    @Test
    fun a_rejected_settlement_is_traced_as_rejected() {
        AuditFixture.create().use { fixture ->
            val record = fixture.audit.settleAudited(
                settle = AuditFixture.settle(status = SettleStatus.REJECTED),
                approved = false,
            )

            assertEquals("结算审核驳回", record.summary)
            assertEquals("REJECTED", record.details["status"])
        }
    }

    @Test
    fun query_filters_by_target_action_and_operator() {
        AuditFixture.create().use { fixture ->
            fixture.audit.priceChanged(AuditFixture.priceLog())
            fixture.audit.priceChanged(AuditFixture.priceLog(customerId = 12, batchTag = "B2"))
            fixture.audit.settleAudited(AuditFixture.settle(), approved = true)

            assertEquals(1, fixture.audit.query(AuditQuery(targetNo = "11/22/A1")).size)
            assertEquals(2, fixture.audit.query(AuditQuery(action = AuditAction.PRICE_CHANGE)).size)
            assertEquals(1, fixture.audit.query(AuditQuery(operatorId = 9)).size)
            assertEquals(
                0,
                fixture.audit.query(AuditQuery(action = AuditAction.SETTLE_AUDIT, operatorId = 7)).size,
            )
        }
    }

    @Test
    fun query_returns_the_newest_first_and_honours_the_limit() {
        AuditFixture.create().use { fixture ->
            fixture.audit.priceChanged(AuditFixture.priceLog(batchTag = "A1"))
            fixture.audit.priceChanged(AuditFixture.priceLog(batchTag = "A2"))
            fixture.audit.priceChanged(AuditFixture.priceLog(batchTag = "A3"))

            val all = fixture.audit.query(AuditQuery(action = AuditAction.PRICE_CHANGE))
            assertEquals(listOf("11/22/A3", "11/22/A2", "11/22/A1"), all.map { it.targetNo })
            assertEquals(2, fixture.audit.query(AuditQuery(limit = 2)).size)
        }
    }

    @Test
    fun audit_ids_are_strictly_increasing_and_rows_are_never_rewritten() {
        AuditFixture.create().use { fixture ->
            val first = fixture.audit.priceChanged(AuditFixture.priceLog())
            val second = fixture.audit.priceChanged(AuditFixture.priceLog(newPrice = "130.00"))

            assertTrue(second.id > first.id, "自增 id 必须递增（台账可当时间序）")
            val rows = fixture.audit.query(AuditQuery(action = AuditAction.PRICE_CHANGE))
            assertEquals(2, rows.size, "同一目标的新旧两条留痕都要保留，不能覆盖")
            assertEquals("100.00", rows.last().details["old_price"])
        }
    }

    @Test
    fun details_round_trip_through_storage() {
        AuditFixture.create().use { fixture ->
            val details = mapOf("含中文" to "值\n换行", "quote" to "\"引号\"", "empty" to "")
            val record = fixture.audit.record(
                AuditEvent(
                    action = AuditAction.PRICE_CHANGE,
                    targetNo = "1/2/-",
                    operatorId = 1,
                    details = details,
                ),
            )

            assertEquals(details, fixture.audit.query(AuditQuery(targetNo = record.targetNo)).single().details)
            assertNull(record.traceId)
        }
    }

    @Test
    fun a_trace_id_is_kept_for_troubleshooting() {
        AuditFixture.create().use { fixture ->
            val record = fixture.audit.priceChanged(AuditFixture.priceLog(), traceId = "trace-42")
            assertEquals("trace-42", record.traceId)
        }
    }

    @Test
    fun record_rejects_invalid_inputs_with_e422_and_writes_nothing() {
        AuditFixture.create().use { fixture ->
            fun assertRejected(event: AuditEvent) {
                val failure = assertFailsWith<AuditException> { fixture.audit.record(event) }
                assertEquals(ErrorCode.E422, failure.code)
            }

            assertRejected(event(targetNo = "   "))
            assertRejected(event(operatorId = 0))
            assertRejected(event(summary = "x".repeat(AuditEvent.MAX_SUMMARY_LENGTH + 1)))
            assertRejected(
                event(details = (1..AuditEvent.MAX_DETAIL_ENTRIES + 1).associate { "$it" to "v" }),
            )
            assertRejected(event(details = mapOf("k" to "v".repeat(AuditEvent.MAX_DETAIL_LENGTH + 1))))
            assertRejected(event(details = mapOf("   " to "v")))

            assertEquals(0, fixture.audit.query().size, "校验失败不得留下任何行")
        }
    }

    @Test
    fun query_rejects_an_out_of_range_limit() {
        AuditFixture.create().use { fixture ->
            val failure = assertFailsWith<AuditException> {
                fixture.audit.query(AuditQuery(limit = AuditQuery.MAX_LIMIT + 1))
            }
            assertEquals(ErrorCode.E422, failure.code)
        }
    }

    @Test
    fun settle_audit_requires_an_audited_settlement() {
        AuditFixture.create().use { fixture ->
            val failure = assertFailsWith<AuditException> {
                fixture.audit.settleAudited(
                    settle = AuditFixture.settle(status = SettleStatus.SUBMITTED, auditedBy = null),
                    approved = true,
                )
            }
            assertEquals(ErrorCode.E422, failure.code)
        }
    }

    @Test
    fun settle_audit_rejects_a_conclusion_that_contradicts_the_settlement_status() {
        AuditFixture.create().use { fixture ->
            val failure = assertFailsWith<AuditException> {
                fixture.audit.settleAudited(
                    settle = AuditFixture.settle(status = SettleStatus.REJECTED),
                    approved = true,
                )
            }
            assertEquals(ErrorCode.E422, failure.code)
            assertEquals(0, fixture.audit.query().size)
        }
    }

    @Test
    fun price_audit_requires_an_admin_id() {
        AuditFixture.create().use { fixture ->
            val failure = assertFailsWith<AuditException> {
                fixture.audit.priceChanged(AuditFixture.priceLog(adminId = 0))
            }
            assertEquals(ErrorCode.E422, failure.code)
        }
    }

    private fun event(
        targetNo: String = "1/2/-",
        operatorId: Long = 1,
        summary: String? = null,
        details: Map<String, String> = emptyMap(),
    ): AuditEvent = AuditEvent(
        action = AuditAction.PRICE_CHANGE,
        targetNo = targetNo,
        operatorId = operatorId,
        summary = summary,
        details = details,
    )
}
