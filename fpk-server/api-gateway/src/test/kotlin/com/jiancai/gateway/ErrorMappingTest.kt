/*
 * @file fpk-server/api-gateway/src/test/kotlin/com/jiancai/gateway/ErrorMappingTest.kt
 * @role 验收「错误码统一」。
 * @exports ErrorMappingTest
 * @depends shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.gateway

import com.jiancai.shared.CONTRACT_VERSION
import com.jiancai.shared.HEADER_CONTRACT_VERSION
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验收「错误码统一」。
 *
 * 断言口径：
 * - HTTP 状态**只**来自 `shared` 的 `ErrorCode.httpStatus`（逐条扫全枚举）；
 * - 业务异常、网关异常、参数异常、未知异常都有稳定映射；
 * - 5xx 不透出内部消息，但一定带 traceId；其它错误码保留业务文案与字段明细。
 */
class ErrorMappingTest {

    @Test
    fun every_error_code_maps_to_its_own_http_status() {
        ErrorCode.entries.forEach { code ->
            val mapped = ErrorMapping.codeOf(GatewayException(code))
            assertEquals(code, mapped, "错误码映射被篡改：$code")
            assertEquals(code.httpStatus, ErrorMapping.responseOf(code).status)
        }
    }

    @Test
    fun a_business_exception_keeps_its_code_and_message() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(get_("/orders/PS-NOPE/receivable", token = "token-staff"))

            assertEquals(404, response.status)
            assertEquals(ErrorCode.E2001, response.errorCode())
            assertEquals(ErrorCode.E2001.message, response.errorResponse().message)
        }
    }

    @Test
    fun an_unknown_failure_becomes_e500_without_leaking_internals() {
        val mapped = ErrorMapping.toResponse(IllegalStateException("SELECT * FROM secret_table"), "t-1")

        assertEquals(ErrorCode.E500, mapped.code)
        assertEquals(ErrorCode.E500.message, mapped.response.message, "5xx 不得下发内部消息")
        assertEquals("t-1", mapped.response.traceId)
    }

    @Test
    fun a_database_failure_becomes_e503_retryable() {
        val mapped = ErrorMapping.toResponse(com.jiancai.db.DbClosedException(), "t-2")
        assertEquals(ErrorCode.E503, mapped.code)
        assertTrue(ErrorCode.E503.retryable)
    }

    @Test
    fun an_illegal_argument_becomes_e422() {
        assertEquals(ErrorCode.E422, ErrorMapping.codeOf(IllegalArgumentException("数量非法")))
    }

    @Test
    fun a_malformed_body_becomes_e400() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(post_("/auth/login", "{not json", token = null))
            assertEquals(400, response.status)
            assertEquals(ErrorCode.E400, response.errorCode())
        }
    }

    @Test
    fun an_unknown_route_is_404_and_a_wrong_method_is_404_too() {
        FakeGateway().use { fixture ->
            val unknown = fixture.gateway.handle(get_("/nope", token = "token-staff"))
            assertEquals(404, unknown.status)
            assertEquals(ErrorCode.E404, unknown.errorCode())

            val wrongMethod = fixture.gateway.handle(post_("/orders/today", "{}", token = "token-staff"))
            assertEquals(404, wrongMethod.status)
            assertEquals(ErrorCode.E404, wrongMethod.errorCode())
        }
    }

    @Test
    fun a_major_contract_mismatch_is_rejected_with_e409() {
        FakeGateway().use { fixture ->
            val request = get_("/contract").copy(
                headers = mapOf(HEADER_CONTRACT_VERSION.lowercase() to "2.0.0"),
            )
            val response = fixture.gateway.handle(request)

            assertEquals(409, response.status)
            assertEquals(ErrorCode.E409, response.errorCode())
            assertEquals(CONTRACT_VERSION, response.errorResponse().details["server"])
        }
    }

    @Test
    fun a_minor_contract_mismatch_is_allowed() {
        FakeGateway().use { fixture ->
            val major = CONTRACT_VERSION.substringBefore('.')
            val request = get_("/contract").copy(
                headers = mapOf(HEADER_CONTRACT_VERSION.lowercase() to "$major.99.0"),
            )
            assertEquals(200, fixture.gateway.handle(request).status)
        }
    }

    @Test
    fun an_oversized_body_is_rejected_with_e413_before_any_side_effect() {
        val fixture = FakeGateway()
        fixture.close()
        // 自定义上限的网关：证明拦截发生在 handler 之前。
        val tight = FakeGateway()
        val tiny = ApiGateway(
            tight.deps.copy(maxBodyBytes = 16),
        )
        val response = tiny.handle(
            post_("/orders", """{"client_request_id":"r-1","order_type":"purchase"}""", token = "token-staff", idempotencyKey = "k"),
        )

        assertEquals(413, response.status)
        assertEquals(ErrorCode.E413, response.errorCode())
        assertEquals(0, tight.world.orderCreateCount)
        tight.close()
    }

    @Test
    fun every_response_carries_the_contract_version_and_a_trace_id() {
        FakeGateway().use { fixture ->
            val ok = fixture.gateway.handle(get_("/contract"))
            assertEquals(CONTRACT_VERSION, ok.headers[HEADER_CONTRACT_VERSION])
            val traceId = ok.headers[ApiGateway.HEADER_TRACE_ID]
            assertFalse(traceId.isNullOrBlank())

            val failed = fixture.gateway.handle(get_("/orders/today"))
            assertEquals(401, failed.status)
            assertEquals(failed.headers[ApiGateway.HEADER_TRACE_ID], failed.errorResponse().traceId)
        }
    }

    @Test
    fun a_gateway_exception_keeps_its_field_level_details() {
        val mapped = ErrorMapping.toResponse(
            GatewayException(ErrorCode.E422, "缺参数", mapOf("field" to "customer_id")),
            "t-3",
        )
        assertEquals("customer_id", mapped.response.details["field"])
        assertEquals("缺参数", mapped.response.message)
    }

    @Test
    fun a_print_failure_from_s12_keeps_its_own_code() {
        assertEquals(ErrorCode.E7002, ErrorMapping.codeOf(com.jiancai.print.PrintException(ErrorCode.E7002)))
        assertEquals(ErrorCode.E5003, ErrorMapping.codeOf(com.jiancai.settlement.SettleException(ErrorCode.E5003)))
        assertEquals(ErrorCode.E3003, ErrorMapping.codeOf(com.jiancai.returncheck.ReturnCheckException(ErrorCode.E3003)))
    }
}
