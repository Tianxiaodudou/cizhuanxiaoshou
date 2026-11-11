/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   48 行  类 OrderEntryRouteTest
 * 第   50 行  类 FakeClient
 * 第   54 行  函数 call
 * 第   60 行  函数 serverAddress
 * 第   65 行  函数 filledState
 * 第   72 行  函数 all_four_order_types_share_the_same_component
 * 第   86 行  函数 the_command_carries_only_what_the_client_is_allowed_to_send
 * 第  103 行  函数 submission_goes_to_the_server_and_returns_the_server_order
 * 第  118 行  函数 local_validation_stops_an_incomplete_form_before_the_network
 * 第  131 行  函数 the_server_failure_is_returned_verbatim
 * 第  140 行  函数 a_return_order_carries_the_source_order_number
 * 第  151 行  函数 prefill_copies_the_server_values_without_recomputing
 * 第  161 行  函数 the_module_never_computes_amounts_or_numbers
 * 第  176 行  对象 ?
 * 第  177 行  函数 sampleOrder
 */

package com.jiancai.android.orderentry

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiRequestSpec
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.LineDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PricingUnit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C06 验收：4 类页共用组件；提交走服务端。
 *
 * 硬边界用例：客户端不上传单价 / 金额 / 单号 / 日期，也不计算它们。
 */
class OrderEntryRouteTest {

    private class FakeClient(
        private val order: ApiResult<OrderDto> = ApiResult.Success(sampleOrder()),
    ) : ApiClient {
        val calls = mutableListOf<ApiRequestSpec<*>>()
        override fun <T> call(spec: ApiRequestSpec<T>): ApiResult<T> {
            calls += spec
            @Suppress("UNCHECKED_CAST")
            return order as ApiResult<T>
        }

        override fun serverAddress(): String = "http://127.0.0.1:8080"
    }

    private val customer = CustomerDto(11, "张三", "13800000000", "长安街 1 号", "2026-11-01T09:00:00+08:00")

    private fun filledState(type: OrderType = OrderType.PURCHASE): OrderFormState =
        OrderFormComponent.addLine(
            OrderFormComponent.withCustomer(OrderFormComponent.empty(type), customer),
            OrderFormLine(categoryId = 22, batchTag = "A1", unit = PricingUnit.BAG, quantity = "2"),
        )

    @Test
    fun all_four_order_types_share_the_same_component() {
        val states = OrderType.entries.map { filledState(it) }

        assertEquals(4, states.size)
        states.forEach { state ->
            // 同一个组件生成的屏，只有标题里的单据类型不同
            val screen = OrderFormComponent.screen(state)
            assertEquals("/orders/new", screen.id)
            assertEquals("${state.orderType.label}录入", screen.title)
            assertEquals(screen.buttons.map { it.id }, listOf("submit", "cancel"))
        }
    }

    @Test
    fun the_command_carries_only_what_the_client_is_allowed_to_send() {
        val state = filledState()
        val cmd = OrderFormComponent.toCommand(state, clientRequestId = "req-1")

        assertEquals(OrderType.PURCHASE, cmd.orderType)
        assertEquals(11, cmd.customerId)
        assertEquals(1, cmd.lines.size)
        assertEquals("2", cmd.lines.single().quantity)
        assertNull(cmd.sourceOrderNo)
        // 命令类型里根本没有单价 / 金额 / 单号 / 日期字段（编译期保证 + 线格式断言）
        val wire = com.jiancai.shared.ContractJson.json.encodeToString(CreateOrderCmd.serializer(), cmd)
        listOf("\"unit_price\"", "\"total_amount\"", "\"order_no\"", "\"order_date\"").forEach { forbidden ->
            assertFalse(wire.contains(forbidden), "下单入参不得包含 $forbidden")
        }
    }

    @Test
    fun submission_goes_to_the_server_and_returns_the_server_order() {
        val client = FakeClient()
        val route = OrderEntryRoute(client, OrderType.PURCHASE)

        val result = route.submit(filledState(), clientRequestId = "req-1")

        val order = assertIs<ApiResult.Success<OrderDto>>(result).value
        assertEquals("PS202611110001", order.orderNo, "单号必须来自服务端")
        assertEquals(1, client.calls.size)
        assertEquals("/orders", client.calls.single().path)
        assertEquals("POST", client.calls.single().method.name)
        assertEquals("req-1", client.calls.single().idempotencyKey, "幂等键必须复用 client_request_id")
    }

    @Test
    fun local_validation_stops_an_incomplete_form_before_the_network() {
        val client = FakeClient()
        val route = OrderEntryRoute(client, OrderType.PURCHASE)

        val empty = route.submit(OrderFormComponent.empty(OrderType.PURCHASE), "req-1")
        assertEquals(ErrorCode.E422, assertIs<ApiResult.Failure>(empty).code)

        val noLines = OrderFormComponent.withCustomer(OrderFormComponent.empty(OrderType.DIRECT), customer)
        assertEquals(ErrorCode.E422, assertIs<ApiResult.Failure>(route.submit(noLines, "req-1")).code)
        assertEquals(0, client.calls.size, "本地校验失败不得发请求")
    }

    @Test
    fun the_server_failure_is_returned_verbatim() {
        val failure = ApiResult.Failure("E2003", ErrorCode.E2003, "仅当日订单可修改", 409)
        val route = OrderEntryRoute(FakeClient(failure), OrderType.PURCHASE)

        val result = route.submit(filledState(), "req-1")
        assertEquals("仅当日订单可修改", assertIs<ApiResult.Failure>(result).message)
    }

    @Test
    fun a_return_order_carries_the_source_order_number() {
        val client = FakeClient()
        val route = OrderEntryRoute(client, OrderType.PURCHASE_RETURN)

        route.submit(filledState(OrderType.PURCHASE_RETURN), "req-2", sourceOrderNo = "PS202611110001")

        val body = client.calls.single().bodyJson!!
        assertTrue(body.contains("\"source_order_no\":\"PS202611110001\""), body)
    }

    @Test
    fun prefill_copies_the_server_values_without_recomputing() {
        val order = sampleOrder(lineQuantity = "1.500", unitPrice = "100.00", amount = "150.00")
        val state = OrderFormComponent.prefillFrom(order)

        assertEquals("1.500", state.lines.single().quantity, "数量必须逐字复制服务端字符串")
        assertEquals(order.orderNo, state.submitted?.orderNo)
        assertEquals(order.totalAmount.display(), state.submitted?.totalAmount?.display())
    }

    @Test
    fun the_module_never_computes_amounts_or_numbers() {
        val sources = File("src/main/kotlin/com/jiancai/android/orderentry").listFiles()!!
            .filter { it.extension == "kt" }
        val text = sources.joinToString("\n") { it.readText() }

        listOf("Money.ofPlain", "BigDecimal", "multiply", "toDouble", "toBigDecimal", "+ amount").forEach { forbidden ->
            assertFalse(text.contains(forbidden), "录入页不得计算金额（发现 $forbidden）")
        }
        assertFalse(
            Regex("""["'](?:PS|PT|ZS|ZT|BZ)\$?\{?""").containsMatchIn(text),
            "录入页不得拼单号",
        )
        assertFalse(text.contains("LocalDate.now"), "录入页不得自己定业务日期")
    }

    companion object {
        fun sampleOrder(
            lineQuantity: String = "2",
            unitPrice: String = "100.00",
            amount: String = "200.00",
        ): OrderDto = OrderDto(
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
                    quantity = lineQuantity,
                    unitPrice = Money.ofPlain(unitPrice),
                    amount = Money.ofPlain(amount),
                ),
            ),
            totalAmount = Money.ofPlain(amount),
            receivableAmount = Money.ofPlain(amount),
            receivedAmount = Money.ofPlain("0.00"),
            version = 1,
            createdAt = "2026-11-11T09:00:00+08:00",
            createdBy = 2,
        )
    }
}
