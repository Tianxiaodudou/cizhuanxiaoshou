/*
 * @file fpk-server/api-gateway/src/test/kotlin/com/jiancai/gateway/IdempotencyTest.kt
 * @role 验收「幂等键校验」。
 * @exports IdempotencyTest
 * @depends shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.gateway

import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 验收「幂等键校验」。
 *
 * 断言口径：
 * - 写类接口没有 `Idempotency-Key` → `E422`，并且**业务 Port 一次都没被调用**；
 * - 同一个 key + 同一份请求 → 直接重放首次响应（业务只执行一次，返回 `X-Idempotent-Replay: true`）；
 * - 同一个 key + 不同请求 → `E409`（客户端 bug，不能把两次不同操作折叠成一次）；
 * - 失败的请求不占用 key，客户端可以用同一个 key 重试。
 */
class IdempotencyTest {

    private val orderBody = """
        {"client_request_id":"r-1","order_type":"purchase","customer_id":11,
         "lines":[{"category_id":22,"batch_tag":"A1","unit":"bag","quantity":"2"}]}
    """.trimIndent()

    @Test
    fun a_write_endpoint_without_an_idempotency_key_is_rejected_before_any_side_effect() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(post_("/orders", orderBody, token = "token-staff"))

            assertEquals(422, response.status)
            assertEquals(ErrorCode.E422, response.errorCode())
            assertEquals(0, fixture.world.orderCreateCount, "缺幂等键时不得建单")
        }
    }

    @Test
    fun a_blank_idempotency_key_is_rejected() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(
                post_("/orders", orderBody, token = "token-staff", idempotencyKey = "   "),
            )
            assertEquals(422, response.status)
            assertEquals(0, fixture.world.orderCreateCount)
        }
    }

    @Test
    fun the_same_key_and_the_same_request_replays_the_first_response() {
        FakeGateway().use { fixture ->
            val first = fixture.gateway.handle(
                post_("/orders", orderBody, token = "token-staff", idempotencyKey = "k-1"),
            )
            val second = fixture.gateway.handle(
                post_("/orders", orderBody, token = "token-staff", idempotencyKey = "k-1"),
            )

            assertEquals(201, first.status)
            assertEquals(201, second.status)
            assertEquals("true", second.headers[IdempotencyGuard.HEADER_IDEMPOTENT_REPLAY])
            assertEquals(first.body, second.body, "重放必须逐字节一致")
            assertEquals(1, fixture.world.orderCreateCount, "同一个幂等键只能执行一次")
            assertNotNull(first.decode(OrderDto.serializer()).orderNo)
        }
    }

    @Test
    fun the_same_key_with_a_different_request_is_rejected_with_e409() {
        FakeGateway().use { fixture ->
            fixture.gateway.handle(post_("/orders", orderBody, token = "token-staff", idempotencyKey = "k-1"))
            val changed = orderBody.replace("\"quantity\":\"2\"", "\"quantity\":\"3\"")
            val response = fixture.gateway.handle(
                post_("/orders", changed, token = "token-staff", idempotencyKey = "k-1"),
            )

            assertEquals(409, response.status)
            assertEquals(ErrorCode.E409, response.errorCode())
            assertEquals(1, fixture.world.orderCreateCount, "冲突不应再执行一次")
        }
    }

    @Test
    fun a_failed_request_does_not_burn_the_key() {
        FakeGateway().use { fixture ->
            val broken = orderBody.replace("\"customer_id\":11", "\"customer_id\":")
            val failed = fixture.gateway.handle(
                post_("/orders", broken, token = "token-staff", idempotencyKey = "k-1"),
            )
            assertEquals(400, failed.status)
            assertEquals(0, fixture.world.orderCreateCount)

            val retried = fixture.gateway.handle(
                post_("/orders", orderBody, token = "token-staff", idempotencyKey = "k-1"),
            )
            assertEquals(201, retried.status, "失败后同一个 key 必须可以重试")
            assertEquals(1, fixture.world.orderCreateCount)
        }
    }

    @Test
    fun notes_and_settlements_are_idempotent_too() {
        FakeGateway().use { fixture ->
            val note = """{"source_order_no":"PS1","diff_type":"quantity","diff_amount":"5.00","remark":"少 5 元"}"""
            val noteFirst = fixture.gateway.handle(
                post_("/notes", note, token = "token-staff", idempotencyKey = "n-1"),
            )
            val noteSecond = fixture.gateway.handle(
                post_("/notes", note, token = "token-staff", idempotencyKey = "n-1"),
            )
            assertEquals(201, noteFirst.status)
            assertEquals("true", noteSecond.headers[IdempotencyGuard.HEADER_IDEMPOTENT_REPLAY])
            assertEquals(1, fixture.world.noteCreateCount)

            val settle = """{"client_request_id":"s-1","order_no":"PS1","direction":"receipt","amount":"100.00"}"""
            fixture.gateway.handle(post_("/settlements", settle, token = "token-staff", idempotencyKey = "s-1"))
            fixture.gateway.handle(post_("/settlements", settle, token = "token-staff", idempotencyKey = "s-1"))
            assertEquals(1, fixture.world.settleSubmitCount)
        }
    }

    @Test
    fun a_non_idempotent_route_ignores_the_key() {
        FakeGateway().use { fixture ->
            val order = fixture.world.newOrder(returnOrderCmd())
            val body = """{"order_no":"${order.orderNo}","version":1,"lines":[]}"""
            fixture.gateway.handle(put_("/orders/${order.orderNo}", body, token = "token-staff"))
            fixture.gateway.handle(put_("/orders/${order.orderNo}", body, token = "token-staff"))

            assertEquals(2, fixture.world.orderUpdateCount, "改单不是幂等接口，两次都要执行")
        }
    }

    @Test
    fun an_over_long_key_is_rejected() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(
                post_("/orders", orderBody, token = "token-staff", idempotencyKey = "k".repeat(200)),
            )
            assertEquals(422, response.status)
            assertEquals(0, fixture.world.orderCreateCount)
        }
    }

    @Test
    fun the_store_is_bounded_and_keeps_only_successful_responses() {
        val store = InMemoryIdempotencyStore(maxEntries = 2)
        val guard = IdempotencyGuard(store)
        val request = post_("/orders", "{}", token = "token-staff", idempotencyKey = "k")
        val key = guard.requireKey(request)
        val fingerprint = guard.fingerprint(request)

        guard.remember(key, fingerprint, ApiResponse(201, "{}"))
        assertEquals("true", guard.replay(key, fingerprint)!!.headers[IdempotencyGuard.HEADER_IDEMPOTENT_REPLAY])
        guard.release(key)
        assertTrue(guard.replay(key, fingerprint) == null, "释放后不应再重放")
    }
}

private fun returnOrderCmd() = com.jiancai.shared.dto.CreateOrderCmd(
    clientRequestId = "seed-1",
    orderType = com.jiancai.shared.enums.OrderType.PURCHASE,
    customerId = 11,
    lines = emptyList(),
)
