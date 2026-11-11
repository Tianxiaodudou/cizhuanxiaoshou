/*
 * @file android-app/feature-query/src/test/kotlin/com/jiancai/android/query/QueryRouteTest.kt
 * @role C07 验收：只调聚合接口。
 * @exports QueryRouteTest
 * @depends core-network, shared
 * @calledBy （无）
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.query

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiRequestSpec
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.errors.ErrorCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * C07 验收：只调聚合接口。
 *
 * 断言：唯一发出的请求是 `GET /orders?phone=...`；源码里没有 SQL / 本地库 / 金额计算。
 */
class QueryRouteTest {

    private class FakeClient(private val result: ApiResult<PageDto<OrderDto>>) : ApiClient {
        val requests = mutableListOf<ApiRequestSpec<*>>()
        override fun <T> call(spec: ApiRequestSpec<T>): ApiResult<T> {
            requests += spec
            @Suppress("UNCHECKED_CAST")
            return result as ApiResult<T>
        }

        override fun serverAddress(): String = "http://127.0.0.1:8080"
    }

    @Test
    fun a_phone_query_uses_the_single_aggregate_endpoint() {
        val client = FakeClient(ApiResult.Success(PageDto(emptyList(), 0)))
        QueryRoute.search(client, " 13800000000 ")

        val request = client.requests.single()
        assertEquals("/orders", request.path)
        assertEquals("GET", request.method.name)
        assertEquals("13800000000", request.query["phone"])
        assertEquals("1", request.query["page"])
    }

    @Test
    fun an_empty_phone_is_rejected_without_a_request() {
        val client = FakeClient(ApiResult.Success(PageDto(emptyList(), 0)))
        val result = QueryRoute.search(client, "   ")

        assertEquals(ErrorCode.E422, assertIs<ApiResult.Failure>(result).code)
        assertEquals(0, client.requests.size)
    }

    @Test
    fun the_page_is_passed_through_for_pagination() {
        val client = FakeClient(ApiResult.Success(PageDto(emptyList(), 0)))
        QueryRoute.search(client, "13800000000", page = 3)
        assertEquals("3", client.requests.single().query["page"])
    }

    @Test
    fun the_feature_contains_no_local_storage_or_sql() {
        val sources = File("src/main/kotlin/com/jiancai/android/query").listFiles()!!
            .filter { it.extension == "kt" }
        val text = sources.joinToString("\n") { it.readText() }

        listOf("SELECT ", "INSERT ", "sqlite", "Room", "Database").forEach { forbidden ->
            assertFalse(text.contains(forbidden, ignoreCase = true), "历史查询不得本地拼 SQL / 直连（发现 $forbidden）")
        }
        assertFalse(text.contains("Money.ofPlain"), "不得计算金额")
    }

    @Test
    fun the_screen_only_needs_a_phone_and_a_search_button() {
        val screen = QueryRoute.screen()
        assertEquals("/query", screen.id)
        assertTrue(screen.fields.any { it.name == "phone" })
        assertTrue(screen.buttons.any { it.id == "query_search" })
    }
}
