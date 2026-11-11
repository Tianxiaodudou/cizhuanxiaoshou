/*
 * @file fpk-server/api-gateway/src/test/kotlin/com/jiancai/gateway/RoutesBehaviourTest.kt
 * @role 路由行为（每个业务模块一个入口用例）：证明「网关只转发、DTO 由服务端产出」。
 * @exports RoutesBehaviourTest
 * @depends observability, shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.gateway

import com.jiancai.observability.AuditAction
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.AuditEntryDto
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.ContractInfoDto
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.dto.PriceDto
import com.jiancai.shared.dto.ReceivableDto
import com.jiancai.shared.dto.ReturnCheckResultDto
import com.jiancai.shared.dto.ReturnableBatchDto
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.errors.ErrorCode
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 路由行为（每个业务模块一个入口用例）：证明「网关只转发、DTO 由服务端产出」。
 *
 * 这里不重复业务规则（各模块自己测），只断言：
 * 正确的路径 / 方法 / 角色能打到正确 Port，响应体是服务端的 DTO，副作用发生一次。
 */
class RoutesBehaviourTest {

    private val purchaseBody = """
        {"client_request_id":"r-1","order_type":"purchase","customer_id":11,
         "lines":[{"category_id":22,"batch_tag":"A1","unit":"bag","quantity":"2"}]}
    """.trimIndent()

    @Test
    fun contract_handshake_exposes_the_version_and_the_merchant_name() {
        FakeGateway().use { fixture ->
            val body = fixture.gateway.handle(get_("/contract")).decode(ContractInfoDto.serializer())
            assertEquals(com.jiancai.shared.CONTRACT_VERSION, body.contractVersion)
            assertEquals("测试商家", body.merchantName)
            assertEquals("2026-11-11T09:00:00+08:00", body.serverTime)
        }
    }

    @Test
    fun accounts_can_be_listed_created_and_deleted_by_an_admin() {
        FakeGateway().use { fixture ->
            val created = fixture.gateway.handle(
                post_(
                    "/accounts",
                    """{"username":"staff2","password":"secret123","display_name":"店员二","role":"staff"}""",
                    token = "token-admin",
                ),
            )
            assertEquals(201, created.status)
            assertEquals("staff2", created.decode(AccountDto.serializer()).username)

            val listed = fixture.gateway.handle(get_("/accounts", token = "token-admin"))
            assertEquals(200, listed.status)
            assertEquals(3, listed.decode(ListSerializer(AccountDto.serializer())).size)

            val deleted = fixture.gateway.handle(delete_("/accounts/1", token = "token-admin"))
            assertEquals(204, deleted.status)
            assertEquals("", deleted.body)
        }
    }

    @Test
    fun a_customer_can_be_looked_up_by_phone_and_created() {
        FakeGateway().use { fixture ->
            val found = fixture.gateway.handle(
                get_("/customers", mapOf("phone" to "13800000000"), token = "token-staff"),
            )
            assertEquals(11, found.decode(CustomerDto.serializer()).id)

            val created = fixture.gateway.handle(
                post_("/customers", """{"name":"李四","phone":"13900000000"}""", token = "token-staff"),
            )
            assertEquals(201, created.status)
            assertEquals("13900000000", created.decode(CustomerDto.serializer()).phone)

            val missing = fixture.gateway.handle(get_("/customers", mapOf("phone" to "100"), token = "token-staff"))
            assertEquals(404, missing.status)
            assertEquals(ErrorCode.E404, missing.errorCode())
        }
    }

    @Test
    fun the_category_library_supports_listing_and_admin_writes() {
        FakeGateway().use { fixture ->
            val listed = fixture.gateway.handle(get_("/categories", token = "token-staff"))
            assertEquals(1, listed.decode(ListSerializer(CategoryDto.serializer())).size)

            val created = fixture.gateway.handle(
                post_("/categories", """{"category_name":"钢筋","model":"HRB400","unit":"piece"}""", token = "token-admin"),
            )
            assertEquals(201, created.status)
            assertEquals("钢筋", created.decode(CategoryDto.serializer()).categoryName)

            val deleted = fixture.gateway.handle(delete_("/categories/22", token = "token-admin"))
            assertEquals(204, deleted.status)
        }
    }

    @Test
    fun the_price_can_be_resolved_by_an_authenticated_user() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(
                get_(
                    "/prices",
                    mapOf("customer_id" to "11", "category_id" to "22", "batch_tag" to "A1"),
                    token = "token-staff",
                ),
            )
            assertEquals(200, response.status)
            assertEquals("100.00", response.decode(PriceDto.serializer()).unitPrice.display())
        }
    }

    @Test
    fun an_admin_price_change_is_traced_through_s14() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(
                post_(
                    "/prices/change",
                    """{"customer_id":11,"category_id":22,"batch_tag":"A1","new_price":"120.00","reason":"议价"}""",
                    token = "token-admin",
                ),
            )

            assertEquals(200, response.status)
            assertEquals(1, fixture.world.priceChangeCount)
            assertEquals(1, fixture.world.auditEvents.size, "改价必须留痕")
            val event = fixture.world.auditEvents.single()
            assertEquals(AuditAction.PRICE_CHANGE, event.action)
            assertEquals(1, event.operatorId, "留痕操作者必须是已认证管理员")
            assertTrue(event.traceId != null, "留痕应带网关追踪 id")
        }
    }

    @Test
    fun an_order_is_created_with_server_produced_numbers_and_amounts() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(
                post_("/orders", purchaseBody, token = "token-staff", idempotencyKey = "k-1"),
            )

            assertEquals(201, response.status)
            val order = response.decode(OrderDto.serializer())
            assertEquals("PS202611110001", order.orderNo, "单号必须来自服务端")
            assertEquals("100.00", order.totalAmount.display())
            assertEquals(1, fixture.world.orderCreateCount)
        }
    }

    @Test
    fun a_return_order_without_a_source_order_is_rejected_before_creation() {
        FakeGateway().use { fixture ->
            val body = purchaseBody.replace("\"order_type\":\"purchase\"", "\"order_type\":\"purchase_return\"")
            val response = fixture.gateway.handle(
                post_("/orders", body, token = "token-staff", idempotencyKey = "k-1"),
            )

            assertEquals(422, response.status)
            assertEquals(0, fixture.world.orderCreateCount)
            assertEquals(0, fixture.world.returnValidateCount)
        }
    }

    @Test
    fun a_return_order_is_checked_by_s09_before_it_is_created() {
        FakeGateway().use { fixture ->
            val original = fixture.world.newOrder(
                com.jiancai.shared.dto.CreateOrderCmd(
                    clientRequestId = "seed",
                    orderType = com.jiancai.shared.enums.OrderType.PURCHASE,
                    customerId = 11,
                    lines = emptyList(),
                ),
            )
            val body = """
                {"client_request_id":"r-2","order_type":"purchase_return","customer_id":11,
                 "source_order_no":"${original.orderNo}",
                 "lines":[{"category_id":22,"batch_tag":"A1","unit":"bag","quantity":"1"}]}
            """.trimIndent()
            val response = fixture.gateway.handle(
                post_("/orders", body, token = "token-staff", idempotencyKey = "k-2"),
            )

            assertEquals(201, response.status)
            assertEquals(1, fixture.world.returnValidateCount, "退货单必须先经 S09 校验")
            assertEquals(original.orderNo, response.decode(OrderDto.serializer()).sourceOrderNo)
        }
    }

    @Test
    fun a_return_order_for_an_unknown_source_is_404() {
        FakeGateway().use { fixture ->
            val body = """
                {"client_request_id":"r-3","order_type":"direct_return","customer_id":11,
                 "source_order_no":"ZS-NOPE",
                 "lines":[{"category_id":22,"batch_tag":"A1","unit":"bag","quantity":"1"}]}
            """.trimIndent()
            val response = fixture.gateway.handle(
                post_("/orders", body, token = "token-staff", idempotencyKey = "k-3"),
            )

            assertEquals(404, response.status)
            assertEquals(ErrorCode.E2001, response.errorCode())
            assertEquals(0, fixture.world.orderCreateCount)
        }
    }

    @Test
    fun today_and_history_lists_are_served_from_s08() {
        FakeGateway().use { fixture ->
            placeholderOrder(fixture)

            val today = fixture.gateway.handle(get_("/orders/today", token = "token-staff"))
            assertEquals(1, today.decode(ListSerializer(OrderDto.serializer())).size)

            val page = fixture.gateway.handle(
                get_("/orders", mapOf("order_no" to "PS202611110001", "page_size" to "5"), token = "token-staff"),
            )
            assertEquals(1, page.decode(PageDto.serializer(OrderDto.serializer())).total)
        }
    }

    @Test
    fun an_update_whose_body_disagrees_with_the_path_is_rejected() {
        FakeGateway().use { fixture ->
            val order = placeholderOrder(fixture)
            val body = """{"order_no":"PS-OTHER","version":1,"lines":[]}"""
            val response = fixture.gateway.handle(put_("/orders/${order.orderNo}", body, token = "token-staff"))

            assertEquals(422, response.status)
            assertEquals(0, fixture.world.orderUpdateCount)
        }
    }

    @Test
    fun returnable_batches_and_return_check_are_forwarded_to_s09() {
        FakeGateway().use { fixture ->
            val order = placeholderOrder(fixture)

            val batches = fixture.gateway.handle(
                get_("/orders/${order.orderNo}/returnable-batches", token = "token-staff"),
            )
            assertEquals(200, batches.status)
            assertEquals(1, batches.decode(ListSerializer(ReturnableBatchDto.serializer())).size)

            val check = fixture.gateway.handle(
                post_(
                    "/orders/${order.orderNo}/return-check",
                    """[{"category_id":22,"batch_tag":"A1","unit":"bag","quantity":"2"}]""",
                    token = "token-staff",
                ),
            )
            assertEquals(200, check.status)
            assertEquals(1, check.decode(ReturnCheckResultDto.serializer()).lines.size)
            assertEquals(1, fixture.world.returnValidateCount)
        }
    }

    @Test
    fun a_note_can_be_created_and_voided() {
        FakeGateway().use { fixture ->
            val created = fixture.gateway.handle(
                post_(
                    "/notes",
                    """{"source_order_no":"PS202611110001","diff_type":"quantity","diff_amount":"5.00","remark":"少 5 元"}""",
                    token = "token-staff",
                    idempotencyKey = "n-1",
                ),
            )
            assertEquals(201, created.status)
            val note = created.decode(NoteDto.serializer())
            assertEquals(2, note.createdBy)

            val voided = fixture.gateway.handle(
                post_(
                    "/notes/${note.noteNo}/void",
                    """{"note_no":"${note.noteNo}","reason":"录错"}""",
                    token = "token-staff",
                ),
            )
            assertEquals(200, voided.status)
            assertTrue(voided.decode(NoteDto.serializer()).voided)
        }
    }

    @Test
    fun a_settlement_can_be_submitted_and_audited_with_a_trace() {
        FakeGateway().use { fixture ->
            val submitted = fixture.gateway.handle(
                post_(
                    "/settlements",
                    """{"client_request_id":"s-1","order_no":"PS202611110001","direction":"receipt","amount":"100.00"}""",
                    token = "token-staff",
                    idempotencyKey = "s-1",
                ),
            )
            assertEquals(201, submitted.status)
            val settleNo = submitted.decode(SettleDto.serializer()).settleNo

            val audited = fixture.gateway.handle(
                post_("/settlements/$settleNo/audit", """{"settle_no":"$settleNo","approved":true}""", token = "token-admin"),
            )
            assertEquals(200, audited.status)
            assertEquals(1, fixture.world.settleAuditCount)
            assertEquals(1, fixture.world.auditEvents.size, "审核必须留痕")
            assertEquals(AuditAction.SETTLE_AUDIT, fixture.world.auditEvents.single().action)
        }
    }

    @Test
    fun the_balance_read_model_is_served_from_s11() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(get_("/customers/11/balance", token = "token-staff"))
            assertEquals(200, response.status)
            val balance = response.decode(ReceivableDto.serializer())
            assertEquals("100.00", balance.receivableAmount.display())
            assertEquals("40.00", balance.receivedAmount.display())
            assertEquals("60.00", balance.unreceivedAmount.display())
        }
    }

    @Test
    fun printing_returns_the_server_rendered_ticket_as_text() {
        FakeGateway().use { fixture ->
            val first = fixture.gateway.handle(get_("/print/PS202611110001", token = "token-staff"))
            val second = fixture.gateway.handle(get_("/print/PS202611110001", token = "token-staff"))

            assertEquals(200, first.status)
            assertEquals(ApiResponse.TEXT_PLAIN, first.contentType)
            assertTrue(first.body.contains("单据号：PS202611110001"))
            assertEquals("1", first.headers["X-Print-Count"])
            assertEquals("2", second.headers["X-Print-Count"])
            assertEquals(2, fixture.world.printCount)
        }
    }

    @Test
    fun money_is_returned_as_the_server_produced_string() {
        FakeGateway().use { fixture ->
            val order = placeholderOrder(fixture)
            val response = fixture.gateway.handle(get_("/orders/${order.orderNo}/receivable", token = "token-staff"))
            assertEquals(200, response.status)
            assertEquals("\"100.00\"", response.body, "金额线格式必须是两位小数字符串")
        }
    }

    @Test
    fun the_audit_trail_is_readable_by_an_admin_only() {
        FakeGateway().use { fixture ->
            fixture.gateway.handle(
                post_(
                    "/prices/change",
                    """{"customer_id":11,"category_id":22,"batch_tag":"A1","new_price":"120.00","reason":"议价"}""",
                    token = "token-admin",
                ),
            )

            val staff = fixture.gateway.handle(get_("/audit", token = "token-staff"))
            assertEquals(403, staff.status, "审计只有管理员能看")

            val admin = fixture.gateway.handle(get_("/audit", token = "token-admin"))
            assertEquals(200, admin.status)
            val entries = admin.decode(ListSerializer(AuditEntryDto.serializer()))
            assertEquals(1, entries.size)
            assertEquals("PRICE_CHANGE", entries.single().action)
            assertEquals("11/22/A1", entries.single().targetNo)
        }
    }

    @Test
    fun the_audit_trail_can_be_filtered_by_action() {
        FakeGateway().use { fixture ->
            fixture.world.audits += com.jiancai.observability.AuditRecordDto(
                id = 1,
                action = AuditAction.PRICE_CHANGE,
                targetType = "price",
                targetNo = "11/22/A1",
                operatorId = 1,
                operatorLabel = null,
                summary = "改价",
                details = emptyMap(),
                createdAt = "2026-11-11T09:00:00+08:00",
                traceId = null,
            )
            val filtered = fixture.gateway.handle(
                get_("/audit", mapOf("action" to "PRICE_CHANGE"), token = "token-admin"),
            )
            assertEquals(1, filtered.decode(ListSerializer(AuditEntryDto.serializer())).size)

            val unknown = fixture.gateway.handle(
                get_("/audit", mapOf("action" to "NOPE"), token = "token-admin"),
            )
            assertEquals(422, unknown.status)
        }
    }

    private fun placeholderOrder(fixture: FakeGateway): OrderDto =
        fixture.world.newOrder(
            com.jiancai.shared.dto.CreateOrderCmd(
                clientRequestId = "seed",
                orderType = com.jiancai.shared.enums.OrderType.PURCHASE,
                customerId = 11,
                lines = emptyList<OrderLineCmd>(),
            ),
        )
}
