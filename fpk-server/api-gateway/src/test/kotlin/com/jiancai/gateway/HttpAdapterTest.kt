/*
 * @file fpk-server/api-gateway/src/test/kotlin/com/jiancai/gateway/HttpAdapterTest.kt
 * @role HTTP 适配层（真起一个 JDK HTTP 服务器，用真实 `HttpClient` 打请求）。
 * @exports HttpAdapterTest
 * @depends shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.gateway

import com.jiancai.shared.CONTRACT_VERSION
import com.jiancai.shared.ContractJson
import com.jiancai.shared.HEADER_CONTRACT_VERSION
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.LoginResultDto
import com.jiancai.shared.errors.ErrorCode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP 适配层（真起一个 JDK HTTP 服务器，用真实 `HttpClient` 打请求）。
 *
 * 只验证「字节搬运正确」：方法 / 路径 / 查询串 / 请求头 / UTF-8 请求体 / 响应头 / 状态码。
 * 路由与权限逻辑在别的用例里测，这里不重复。
 */
class HttpAdapterTest {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @Test
    fun a_login_round_trip_returns_a_token_and_the_contract_version() {
        withServer { port ->
            val response = send(
                port,
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"username":"admin","password":"secret"}"""))
                    .build(),
            )

            assertEquals(200, response.statusCode())
            assertEquals(CONTRACT_VERSION, response.headers().firstValue("X-Contract-Version").get())
            val body = ContractJson.json.decodeFromString(LoginResultDto.serializer(), response.body())
            assertEquals("token-admin", body.token)
            assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").get())
        }
    }

    @Test
    fun a_request_without_a_token_gets_a_unified_error_body() {
        withServer { port ->
            val response = send(port, HttpRequest.newBuilder(URI("http://127.0.0.1:$port/orders/today")).GET().build())

            assertEquals(401, response.statusCode())
            assertEquals(
                "E401",
                ContractJson.json.decodeFromString(
                    com.jiancai.shared.errors.ErrorResponse.serializer(),
                    response.body(),
                ).code,
            )
        }
    }

    @Test
    fun query_parameters_are_decoded_and_forwarded() {
        withServer { port ->
            val response = send(
                port,
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/customers?phone=13800000000"))
                    .header("Authorization", "Bearer token-staff")
                    .GET()
                    .build(),
            )

            assertEquals(200, response.statusCode())
            assertEquals("张三", ContractJson.json.decodeFromString(CustomerDto.serializer(), response.body()).name)
        }
    }

    @Test
    fun a_utf8_request_body_survives_the_round_trip() {
        withServer { port ->
            val payload = """{"name":"李四","phone":"13900000000","address":"长安街 1 号"}"""
            val response = send(
                port,
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/customers"))
                    .header("Authorization", "Bearer token-staff")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(),
            )

            assertEquals(201, response.statusCode())
            val created = ContractJson.json.decodeFromString(CustomerDto.serializer(), response.body())
            assertEquals("李四", created.name)
            assertEquals("长安街 1 号", created.address)
        }
    }

    @Test
    fun an_unsupported_method_is_404_and_an_unknown_path_is_404() {
        withServer { port ->
            val patch = send(
                port,
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/orders/today"))
                    .header("Authorization", "Bearer token-staff")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
            )
            assertEquals(404, patch.statusCode())

            val unknown = send(
                port,
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/nope"))
                    .header("Authorization", "Bearer token-staff")
                    .GET()
                    .build(),
            )
            assertEquals(404, unknown.statusCode())
        }
    }

    @Test
    fun every_http_response_carries_a_trace_header() {
        withServer { port ->
            val response = send(port, HttpRequest.newBuilder(URI("http://127.0.0.1:$port/contract")).GET().build())
            assertTrue(response.headers().firstValue(ApiGateway.HEADER_TRACE_ID).isPresent)
            assertEquals(CONTRACT_VERSION, response.headers().firstValue(HEADER_CONTRACT_VERSION).get())
        }
    }

    @Test
    fun the_server_can_be_stopped_and_started_again() {
        FakeGateway().use { fixture ->
            val server = HttpServerAdapter(fixture.gateway)
            val first = server.start(0)
            assertTrue(first > 0)
            assertTrue(server.isRunning)
            server.stop()
            assertTrue(!server.isRunning)

            val second = server.start(0)
            assertTrue(second > 0)
            server.close()
        }
    }

    private fun withServer(block: (Int) -> Unit) {
        FakeGateway().use { fixture ->
            val server = HttpServerAdapter(fixture.gateway)
            val port = server.start(0)
            try {
                block(port)
            } finally {
                server.close()
            }
        }
    }

    private fun send(port: Int, request: HttpRequest): HttpResponse<String> =
        client.send(request, HttpResponse.BodyHandlers.ofString())
}
