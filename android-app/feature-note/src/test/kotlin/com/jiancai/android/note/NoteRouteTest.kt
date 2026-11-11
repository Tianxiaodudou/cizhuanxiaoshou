/*
 * @file android-app/feature-note/src/test/kotlin/com/jiancai/android/note/NoteRouteTest.kt
 * @role C09 验收：备注单正确关联原单；不修改原单。
 * @exports NoteRouteTest
 * @depends core-network, shared
 * @calledBy （无）
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.note

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiRequestSpec
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.LineDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.enums.DiffType
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PricingUnit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * C09 验收：备注单正确关联原单；不修改原单。
 *
 * 断言：创建只调 `POST /notes`（带原单号），作废只调 `/notes/{no}/void`，
 * 本模块从不发 `PUT /orders/...`（不改原单）。
 */
class NoteRouteTest {

    private class FakeClient(
        private val result: ApiResult<NoteDto> = ApiResult.Success(sampleNote()),
    ) : ApiClient {
        val requests = mutableListOf<ApiRequestSpec<*>>()
        override fun <T> call(spec: ApiRequestSpec<T>): ApiResult<T> {
            requests += spec
            @Suppress("UNCHECKED_CAST")
            return result as ApiResult<T>
        }

        override fun serverAddress(): String = "http://127.0.0.1:8080"
    }

    @Test
    fun a_note_is_created_with_the_source_order_number() {
        val client = FakeClient()
        NoteRoute.create(client, " PS202611110001 ", DiffType.PRICE, "5.00", "单价差 5 元", "n-1")

        val request = client.requests.single()
        assertEquals("/notes", request.path)
        assertEquals("POST", request.method.name)
        assertEquals("n-1", request.idempotencyKey)
        assertTrue(request.bodyJson!!.contains("\"source_order_no\":\"PS202611110001\""))
        assertTrue(request.bodyJson!!.contains("\"diff_amount\":\"5.00\""))
    }

    @Test
    fun the_source_order_can_be_selected_from_the_aggregate_query() {
        val page = PageDto(items = listOf(sampleOrder()), total = 1)
        val selected = SelectOrderPage.select(page, "PS202611110001")

        assertEquals("PS202611110001", selected?.orderNo)
        assertEquals(null, SelectOrderPage.select(page, "NOPE"))
    }

    @Test
    fun an_empty_phone_is_rejected_without_a_request() {
        val client = FakeClient()
        val result = SelectOrderPage.search(client, "  ")

        assertEquals(ErrorCode.E422, assertIs<ApiResult.Failure>(result).code)
        assertEquals(0, client.requests.size)
    }

    @Test
    fun voiding_only_targets_the_note_itself() {
        val client = FakeClient()
        NoteRoute.void(client, "BZ202611110001", "录错")

        val request = client.requests.single()
        assertEquals("/notes/BZ202611110001/void", request.path)
        assertFalse(request.path.contains("/orders"), "作废不得改原单")
    }

    @Test
    fun bad_input_is_rejected_before_the_network() {
        val client = FakeClient()
        assertEquals(ErrorCode.E422, assertIs<ApiResult.Failure>(
            NoteRoute.create(client, "", DiffType.PRICE, "5.00", "x", "n-1"),
        ).code)
        assertEquals(ErrorCode.E422, assertIs<ApiResult.Failure>(
            NoteRoute.create(client, "PS1", DiffType.PRICE, "abc", "x", "n-1"),
        ).code)
        assertEquals(ErrorCode.E422, assertIs<ApiResult.Failure>(
            NoteRoute.create(client, "PS1", DiffType.PRICE, "5.00", "  ", "n-1"),
        ).code)
        assertEquals(ErrorCode.E422, assertIs<ApiResult.Failure>(
            NoteRoute.void(client, "BZ1", " "),
        ).code)
        assertEquals(0, client.requests.size)
    }

    @Test
    fun the_module_never_modifies_the_source_order() {
        val sources = File("src/main/kotlin/com/jiancai/android/note").listFiles()!!
            .filter { it.extension == "kt" }
        val text = sources.joinToString("\n") { it.readText() }

        listOf("updateOrder", "UpdateOrderCmd", "PUT").forEach { forbidden ->
            assertFalse(text.contains(forbidden), "备注单不得改原单（发现 $forbidden）")
        }
        assertFalse(
            Regex("""\b(?:totalAmount|receivableAmount)\s*=""").containsMatchIn(text),
            "备注单不得派生原单金额",
        )
    }

    private fun sampleOrder(): OrderDto = OrderDto(
        orderNo = "PS202611110001",
        orderType = OrderType.PURCHASE,
        status = OrderStatus.CREATED,
        customerId = 11,
        customerName = "张三",
        customerPhone = "13800000000",
        orderDate = "2026-11-11",
        lines = listOf(
            LineDto(1, 22, "水泥", "P.O42.5", "A1", PricingUnit.BAG, "2", Money.ofPlain("100.00"), Money.ofPlain("200.00")),
        ),
        totalAmount = Money.ofPlain("200.00"),
        receivableAmount = Money.ofPlain("200.00"),
        receivedAmount = Money.ofPlain("0.00"),
        version = 1,
        createdAt = "2026-11-11T09:00:00+08:00",
        createdBy = 2,
    )
}

private fun sampleNote(): NoteDto = NoteDto(
    noteNo = "BZ202611110001",
    sourceOrderNo = "PS202611110001",
    customerId = 11,
    diffType = DiffType.PRICE,
    diffAmount = Money.ofPlain("5.00"),
    remark = "单价差 5 元",
    createdAt = "2026-11-11T09:00:00+08:00",
    createdBy = 2,
)
