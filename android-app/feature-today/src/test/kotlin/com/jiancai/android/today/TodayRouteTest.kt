/*
 * @file android-app/feature-today/src/test/kotlin/com/jiancai/android/today/TodayRouteTest.kt
 * @role C08 验收：复用录入壳；跨日不可改。
 * @exports TodayRouteTest
 * @depends core-network, core-ui, shared
 * @calledBy （无）
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.today

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiRequestSpec
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.LineDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.android.ui.FieldType
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
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
 * C08 验收：复用录入壳；跨日不可改。
 *
 * 跨日不是客户端判断：本测试断言源码里**没有任何日期比较 / 本地时钟**，
 * 并且服务端 `E2003` 原样回显给用户。
 */
class TodayRouteTest {

    private class FakeClient(
        private val today: ApiResult<List<OrderDto>> = ApiResult.Success(listOf(sampleOrder())),
        private val update: ApiResult<OrderDto> = ApiResult.Success(sampleOrder()),
    ) : ApiClient {
        val requests = mutableListOf<ApiRequestSpec<*>>()
        override fun <T> call(spec: ApiRequestSpec<T>): ApiResult<T> {
            requests += spec
            @Suppress("UNCHECKED_CAST")
            return (if (spec.method.name == "PUT") update else today) as ApiResult<T>
        }

        override fun serverAddress(): String = "http://127.0.0.1:8080"
    }

    @Test
    fun the_today_list_comes_from_the_server_endpoint() {
        val client = FakeClient()
        val result = TodayRoute.load(client)

        assertIs<ApiResult.Success<List<OrderDto>>>(result)
        assertEquals("/orders/today", client.requests.single().path)
    }

    @Test
    fun editing_reuses_the_order_entry_form() {
        val state = TodayRoute.edit(sampleOrder())

        assertEquals(OrderType.PURCHASE, state.orderType)
        assertEquals("13800000000", state.customerPhone)
        assertEquals("2", state.lines.single().quantity)
        // 复用 C06 的同一套屏（字段里含服务端产出的只读金额）
        val amounts = com.jiancai.android.orderentry.OrderFormComponent.screen(state)
            .fields.filter { it.type == FieldType.READONLY }
        assertEquals(listOf("order_no", "total_amount", "receivable_amount"), amounts.map { it.name })
    }

    @Test
    fun saving_sends_the_server_version_unchanged() {
        val client = FakeClient()
        val order = sampleOrder(version = 7)
        val lines = listOf(OrderLineCmd(22, "A1", PricingUnit.BAG, "2"))

        TodayRoute.save(client, order, lines, remark = "  ")

        val request = client.requests.single()
        assertEquals("PUT", request.method.name)
        assertEquals("/orders/${order.orderNo}", request.path)
        assertTrue(request.bodyJson!!.contains("\"version\":7"), "必须原样回传服务端版本")
        assertFalse(request.bodyJson!!.contains("\"remark\":\"  \""), "空白备注应归一为 null")
    }

    @Test
    fun a_cross_day_rejection_from_the_server_is_shown_as_is() {
        val client = FakeClient(
            update = ApiResult.Failure("E2003", ErrorCode.E2003, "仅当日订单可修改", 409),
        )
        val result = TodayRoute.save(client, sampleOrder(), emptyList(), null)

        val failure = assertIs<ApiResult.Failure>(result)
        assertEquals(ErrorCode.E2003, failure.code)
        assertEquals("仅当日订单可修改", failure.message)
    }

    @Test
    fun the_module_never_compares_dates_locally() {
        val sources = File("src/main/kotlin/com/jiancai/android/today").listFiles()!!
            .filter { it.extension == "kt" }
        val text = sources.joinToString("\n") { it.readText() }

        listOf("LocalDate", "LocalDateTime", "Clock", "Instant.now", "orderDate ==").forEach { forbidden ->
            assertFalse(text.contains(forbidden), "跨日判定必须由服务端锁，客户端不得比较日期（发现 $forbidden）")
        }
    }

    @Test
    fun the_today_entry_is_the_second_tab() {
        assertEquals("/today", TodayRoute.entry.id)
        assertEquals(com.jiancai.android.ui.ShellTab.TODAY, TodayRoute.entry.tab)
    }
}

/** 今日单样板（服务端已产出的 DTO；客户端只展示）。 */
internal fun sampleOrder(version: Long = 1): OrderDto = OrderDto(
    orderNo = "PS202611110001",
    orderType = OrderType.PURCHASE,
    status = OrderStatus.CREATED,
    customerId = 11,
    customerName = "张三",
    customerPhone = "13800000000",
    customerAddress = "长安街 1 号",
    orderDate = "2026-11-11",
    lines = listOf(
        LineDto(
            seq = 1,
            categoryId = 22,
            categoryName = "水泥",
            model = "P.O42.5",
            batchTag = "A1",
            unit = PricingUnit.BAG,
            quantity = "2",
            unitPrice = Money.ofPlain("100.00"),
            amount = Money.ofPlain("200.00"),
        ),
    ),
    totalAmount = Money.ofPlain("200.00"),
    receivableAmount = Money.ofPlain("200.00"),
    receivedAmount = Money.ofPlain("0.00"),
    version = version,
    createdAt = "2026-11-11T09:00:00+08:00",
    createdBy = 2,
)
