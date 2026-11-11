/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   46 行  类 ApiClientTest
 * 第   48 行  类 RecordingTransport
 * 第   52 行  函数 exchange
 * 第   58 行  函数 client
 * 第   72 行  函数 the_token_is_injected_on_every_call_and_not_injected_when_logged_out
 * 第   83 行  函数 the_contract_version_is_always_sent
 * 第   90 行  函数 a_write_call_carries_the_idempotency_key_from_the_command
 * 第  108 行  函数 a_successful_login_is_decoded_through_the_shared_contract
 * 第  124 行  函数 a_401_is_parsed_into_the_shared_error_code
 * 第  136 行  函数 every_error_code_from_the_contract_is_parsed_uniformly
 * 第  146 行  函数 an_unknown_error_code_keeps_the_wire_value_and_uses_the_server_message
 * 第  158 行  函数 an_unparseable_error_body_falls_back_to_the_http_status
 * 第  167 行  函数 a_network_failure_becomes_a_retryable_e503_instead_of_an_exception
 * 第  177 行  函数 a_print_call_returns_the_server_rendered_text_verbatim
 * 第  186 行  函数 a_delete_without_a_body_succeeds_without_decoding
 * 第  193 行  函数 query_values_are_url_encoded
 * 第  201 行  函数 the_server_address_is_normalised_and_reported
 * 第  210 行  函数 the_client_never_computes_money_or_order_numbers
 */

package com.jiancai.android.network

import com.jiancai.shared.CONTRACT_VERSION
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.LoginCmd
import com.jiancai.shared.dto.LoginResultDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PricingUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C00 验收：错误码统一解析 + token 自动注入。
 *
 * 用替身传输层直接观察「客户端发出去的字节」，不依赖真实网络。
 */
class ApiClientTest {

    private class RecordingTransport(
        private val respond: (TransportRequest) -> TransportResponse,
    ) : HttpTransport {
        val requests = mutableListOf<TransportRequest>()
        override fun exchange(request: TransportRequest): TransportResponse {
            requests += request
            return respond(request)
        }
    }

    private fun client(
        token: String? = "t-1",
        respond: (TransportRequest) -> TransportResponse = { TransportResponse(200, "{}") },
        address: String = ServerAddress.DEFAULT,
    ): Pair<HttpApiClient, RecordingTransport> {
        val transport = RecordingTransport(respond)
        return HttpApiClient(
            addresses = InMemoryServerAddressStore(address),
            tokens = TokenProvider { token },
            transport = transport,
        ) to transport
    }

    @Test
    fun the_token_is_injected_on_every_call_and_not_injected_when_logged_out() {
        val (loggedIn, transport) = client(token = "abc")
        loggedIn.call(ApiPaths.contract())
        assertEquals("Bearer abc", transport.requests.single().headers["Authorization"])

        val (anonymous, anonymousTransport) = client(token = null)
        anonymous.call(ApiPaths.contract())
        assertNull(anonymousTransport.requests.single().headers["Authorization"])
    }

    @Test
    fun the_contract_version_is_always_sent() {
        val (api, transport) = client()
        api.call(ApiPaths.contract())
        assertEquals(CONTRACT_VERSION, transport.requests.single().headers["X-Contract-Version"])
    }

    @Test
    fun a_write_call_carries_the_idempotency_key_from_the_command() {
        val (api, transport) = client()
        val cmd = CreateOrderCmd(
            clientRequestId = "req-42",
            orderType = OrderType.PURCHASE,
            customerId = 11,
            lines = listOf(OrderLineCmd(22, "A1", PricingUnit.BAG, "2")),
        )
        api.call(ApiPaths.createOrder(cmd))

        val request = transport.requests.single()
        assertEquals("req-42", request.headers["Idempotency-Key"])
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        assertTrue(request.body!!.contains("\"client_request_id\":\"req-42\""))
    }

    @Test
    fun a_successful_login_is_decoded_through_the_shared_contract() {
        val body = """
            {"token":"t-9","expires_at":"2026-11-12T09:00:00+08:00",
             "account":{"id":1,"username":"admin","display_name":"管理员","role":"admin",
                        "enabled":true,"created_at":"2026-11-01T09:00:00+08:00"},
             "contract_version":"$CONTRACT_VERSION"}
        """.trimIndent()
        val (api, _) = client(respond = { TransportResponse(200, body) })

        val result = api.call(ApiPaths.login(LoginCmd("admin", "secret")))
        val login = assertIs<ApiResult.Success<LoginResultDto>>(result).value
        assertEquals("t-9", login.token)
        assertEquals(CONTRACT_VERSION, login.contractVersion)
    }

    @Test
    fun a_401_is_parsed_into_the_shared_error_code() {
        val body = """{"code":"E401","message":"登录已失效，请重新登录","details":{},"trace_id":"tr-1"}"""
        val (api, _) = client(respond = { TransportResponse(401, body) })

        val failure = assertIs<ApiResult.Failure>(api.call(ApiPaths.todayOrders()))
        assertEquals(ErrorCode.E401, failure.code)
        assertEquals(401, failure.httpStatus)
        assertEquals("tr-1", failure.traceId)
        assertTrue(failure.isUnauthorized)
    }

    @Test
    fun every_error_code_from_the_contract_is_parsed_uniformly() {
        ErrorCode.entries.forEach { code ->
            val body = """{"code":"${code.name}","message":"${code.message}"}"""
            val (api, _) = client(respond = { TransportResponse(code.httpStatus, body) })
            val failure = assertIs<ApiResult.Failure>(api.call(ApiPaths.todayOrders()))
            assertEquals(code, failure.code, "错误码解析不一致：$code")
        }
    }

    @Test
    fun an_unknown_error_code_keeps_the_wire_value_and_uses_the_server_message() {
        val body = """{"code":"E8888","message":"服务端新错误"}"""
        val (api, _) = client(respond = { TransportResponse(409, body) })

        val failure = assertIs<ApiResult.Failure>(api.call(ApiPaths.todayOrders()))
        assertEquals("E8888", failure.wireCode)
        assertNull(failure.code, "未知错误码必须为 null，由通用文案兜底")
        assertEquals("服务端新错误", failure.message)
        assertFalse(failure.retryable)
    }

    @Test
    fun an_unparseable_error_body_falls_back_to_the_http_status() {
        val (api, _) = client(respond = { TransportResponse(503, "<html>gateway down</html>") })
        val failure = assertIs<ApiResult.Failure>(api.call(ApiPaths.todayOrders()))

        assertEquals(ErrorCode.E503, failure.code)
        assertTrue(failure.retryable)
    }

    @Test
    fun a_network_failure_becomes_a_retryable_e503_instead_of_an_exception() {
        val (api, _) = client(respond = { throw TransportFailure("connect refused") })
        val failure = assertIs<ApiResult.Failure>(api.call(ApiPaths.todayOrders()))

        assertEquals(ErrorCode.E503, failure.code)
        assertEquals(0, failure.httpStatus)
        assertTrue(failure.retryable)
    }

    @Test
    fun a_print_call_returns_the_server_rendered_text_verbatim() {
        val ticket = "测试商家\n单据号：PS1\n合计：100.00\n"
        val (api, _) = client(respond = { TransportResponse(200, ticket) })

        val result = api.call(ApiPaths.printTicket("PS1"))
        assertEquals(ticket, assertIs<ApiResult.Success<String>>(result).value)
    }

    @Test
    fun a_delete_without_a_body_succeeds_without_decoding() {
        val (api, _) = client(respond = { TransportResponse(204, "") })
        val result = api.call(ApiPaths.deleteAccount(1))
        assertIs<ApiResult.Success<Unit>>(result)
    }

    @Test
    fun query_values_are_url_encoded() {
        val (api, transport) = client()
        api.call(ApiPaths.customerByPhone("138 0000/1"))
        val url = transport.requests.single().url
        assertTrue(url.endsWith("/customers?phone=138+0000%2F1"), url)
    }

    @Test
    fun the_server_address_is_normalised_and_reported() {
        val store = InMemoryServerAddressStore(" http://192.168.1.9:8080/ ")
        assertEquals("http://192.168.1.9:8080", store.baseUrl())
        assertEquals("http://10.0.0.2:9000", store.update("http://10.0.0.2:9000/").value)
        assertTrue(runCatching { store.update("ftp://x") }.isFailure, "非 http(s) 地址必须被拒")
        assertTrue(runCatching { store.update("   ") }.isFailure)
    }

    @Test
    fun the_client_never_computes_money_or_order_numbers() {
        val source = java.io.File("src/main/kotlin/com/jiancai/android/network").listFiles()!!
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(source.contains("Money.ofPlain"), "C00 不得构造金额")
        assertFalse(source.contains("BigDecimal"), "C00 不得做金额运算")
        assertTrue(
            !Regex("""["'](?:PS|PT|ZS|ZT|BZ|JS)\d""").containsMatchIn(source),
            "C00 不得拼单号",
        )
        assertTrue(
            !Regex("""\b(printing|printer)\b""", RegexOption.IGNORE_CASE).containsMatchIn(source),
            "C00 不得包含打印实现（打印只在服务端 S12）",
        )
    }
}
