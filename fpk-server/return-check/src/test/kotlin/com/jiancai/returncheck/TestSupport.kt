/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   85 行  函数 clockAt
 * 第   88 行  函数 testDb
 * 第  100 行  类 Fixture
 * 第  120 行  函数 returnCheck
 * 第  123 行  函数 customer
 * 第  129 行  函数 category
 * 第  147 行  函数 price
 * 第  164 行  函数 order
 * 第  183 行  函数 close
 * 第  187 行  函数 lineCmd
 * 第  200 行  函数 orderDto
 * 第  229 行  函数 lineDto
 * 第  251 行  函数 assertReturnError
 * 第  258 行  类 CountingTxRunner
 * 第  262 行  函数 reset
 * 第  266 行  函数 run
 */

package com.jiancai.returncheck

import com.jiancai.category.CategoryMigrations
import com.jiancai.category.CategoryPort
import com.jiancai.category.CategoryService
import com.jiancai.customer.CustomerMigrations
import com.jiancai.customer.CustomerPort
import com.jiancai.customer.CustomerService
import com.jiancai.db.DbCore
import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.order.OrderMigrations
import com.jiancai.order.OrderPort
import com.jiancai.order.OrderPriceRecalculator
import com.jiancai.order.OrderService
import com.jiancai.pricing.PricePort
import com.jiancai.pricing.PriceService
import com.jiancai.pricing.PricingMigrations
import com.jiancai.serial.SerialMigrations
import com.jiancai.serial.SerialPort
import com.jiancai.serial.SerialService
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.ChangePriceCmd
import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.LineDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PricingUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/*
 * 测试夹具（不是业务代码）：内存库 + 固定时钟 + 真实 S04 / S05 / S06 / S07 / S08 实例。
 *
 * S09 自己没有表、没有 SQL，所以这里只准备「别人拥有的数据」——原单与退货单都经 S08 真实建单
 * 产生，保证测试覆盖的是真实的跨模块读取路径（只经 OrderPort）。
 */

/** 服务端业务时区：与部署环境一致。 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 固定时钟的瞬间（UTC）：上海 2026-11-11 10:00。 */
internal const val NOW_INSTANT: String = "2026-11-11T02:00:00Z"

/** 固定时钟下的服务端业务日期。 */
internal const val TODAY: String = "2026-11-11"

/** 建单 / 改价的操作者 id（S13 已认证后传入）。 */
internal const val OPERATOR_ID: Long = 9L

/** 改价管理员 id。 */
internal const val ADMIN_ID: Long = 7L

internal fun clockAt(instant: String = NOW_INSTANT): Clock = Clock.fixed(Instant.parse(instant), TEST_ZONE)

/** 业务主链 + 单号 + 订单的完整内存库，已迁移到最新版（S09 不贡献任何迁移）。 */
internal fun testDb(): DbCore = DbCore.testDatabase(
    SerialMigrations.all +
        CustomerMigrations.all +
        CategoryMigrations.all +
        PricingMigrations.all +
        OrderMigrations.all,
)

/**
 * 业务夹具：真实 S04 / S05 / S06 / S07 / S08 实例 + 本模块的 [ReturnCheckService]，
 * 共享同一个内存库与固定时钟。
 */
internal class Fixture : java.io.Closeable {
    val db: DbCore = testDb()
    val txRunner: TxRunner = db.txRunner
    val clock: Clock = clockAt()
    val customers: CustomerPort = CustomerService(txRunner, clock)
    val categories: CategoryPort = CategoryService(txRunner, clock)

    /** S06 改价需要 S08 的重算监听器；先建监听器再建 pricing，避免构造环。 */
    val recalculator: OrderPriceRecalculator = OrderPriceRecalculator(txRunner, clock)
    val pricing: PricePort = PriceService(
        txRunner = txRunner,
        customers = customers,
        categories = categories,
        clock = clock,
        listeners = listOf(recalculator),
    )
    val serial: SerialPort = SerialService(txRunner, clock)
    val orders: OrderPort = OrderService(txRunner, serial, pricing, customers, categories, clock)

    /** 本模块实例（可注入不同策略验证「规则可独立升级」）。 */
    fun returnCheck(policy: ReturnCheckPolicy = ReturnCheckPolicy()): ReturnCheckPort =
        ReturnCheckService(txRunner, orders, policy)

    fun customer(
        phone: String = "13800000000",
        name: String = "张三",
    ): CustomerDto = customers.create(CreateCustomerCmd(name = name, phone = phone, address = null))

    /** 建一个型号；默认要求批号且可选 A / B（计价单位吨）。 */
    fun category(
        categoryName: String = "水泥",
        model: String = "P.O 42.5",
        unit: PricingUnit = PricingUnit.TON,
        batchTagRequired: Boolean = true,
        batchTags: List<String> = listOf("A", "B"),
    ): CategoryDto = categories.create(
        CategoryCmd(
            categoryName = categoryName,
            model = model,
            unit = unit,
            batchTagRequired = batchTagRequired,
            batchTags = batchTags,
            packingSpec = null,
        ),
    )

    /** 首次定价（走真实 S06）。 */
    fun price(
        customerId: Long,
        categoryId: Long,
        unitPrice: String = "12.30",
        batchTag: String = "A",
    ) = pricing.changePrice(
        ChangePriceCmd(
            customerId = customerId,
            categoryId = categoryId,
            batchTag = batchTag,
            newPrice = Money.ofPlain(unitPrice),
            reason = "初始定价",
        ),
        ADMIN_ID,
    )

    /** 建单（走真实 S08：取号、定价、金额派生都由真实模块完成）。 */
    fun order(
        customerId: Long,
        lines: List<OrderLineCmd>,
        orderType: OrderType = OrderType.PURCHASE,
        requestId: String = "req-${requestCounter++}",
        sourceOrderNo: String? = null,
    ): OrderDto = orders.create(
        CreateOrderCmd(
            clientRequestId = requestId,
            orderType = orderType,
            customerId = customerId,
            lines = lines,
            sourceOrderNo = sourceOrderNo,
        ),
        OPERATOR_ID,
    )

    private var requestCounter: Int = 1

    override fun close() = db.close()
}

/** 订单行入参：型号 + 批号 + 数量（单位默认吨）。 */
internal fun lineCmd(
    categoryId: Long,
    quantity: String,
    batchTag: String = "A",
    unit: PricingUnit = PricingUnit.TON,
): OrderLineCmd = OrderLineCmd(
    categoryId = categoryId,
    batchTag = batchTag,
    unit = unit,
    quantity = quantity,
)

/** 手工构造订单 DTO（纯规则单测用，不落库）；退货单可带 `sourceOrderNo` 表达 1.1.0 的精确归属。 */
internal fun orderDto(
    orderNo: String = "PS202611110001",
    orderType: OrderType = OrderType.PURCHASE,
    status: OrderStatus = OrderStatus.CREATED,
    customerId: Long = 1L,
    lines: List<LineDto> = listOf(lineDto()),
    sourceOrderNo: String? = null,
): OrderDto = OrderDto(
    orderNo = orderNo,
    orderType = orderType,
    status = status,
    customerId = customerId,
    customerName = "张三",
    customerPhone = "13800000000",
    customerAddress = null,
    orderDate = TODAY,
    lines = lines,
    totalAmount = Money.ofPlain("0.00"),
    receivableAmount = Money.ofPlain("0.00"),
    receivedAmount = Money.ofPlain("0.00"),
    remark = null,
    version = 1L,
    createdAt = "2026-11-11T10:00:00+08:00",
    createdBy = OPERATOR_ID,
    updatedAt = null,
    sourceOrderNo = sourceOrderNo,
)

/** 手工构造订单明细 DTO（纯规则单测用）。 */
internal fun lineDto(
    categoryId: Long = 101L,
    categoryName: String = "水泥",
    model: String = "P.O 42.5",
    batchTag: String = "A",
    unit: PricingUnit = PricingUnit.TON,
    quantity: String = "10",
    seq: Int = 1,
): LineDto = LineDto(
    seq = seq,
    categoryId = categoryId,
    categoryName = categoryName,
    model = model,
    batchTag = batchTag,
    unit = unit,
    quantity = quantity,
    unitPrice = Money.ofPlain("12.30"),
    amount = Money.ofPlain("123.00"),
    packingSpec = null,
)

/** 断言退单错误码（S13 按 code 映射 HTTP，本模块测试只关心 code）。 */
internal fun assertReturnError(code: ErrorCode, block: () -> Unit): ReturnCheckException {
    val failure = assertFailsWith<ReturnCheckException> { block() }
    assertEquals(code, failure.code, "期望 $code，实际 ${failure.code}：${failure.message}")
    return failure
}

/** 事务计数包装：验证便捷重载各开一个事务、带 tx 重载不再自开事务。 */
internal class CountingTxRunner(private val delegate: TxRunner) : TxRunner {
    var runs: Int = 0
        private set

    fun reset() {
        runs = 0
    }

    override fun <T> run(block: (TxContext) -> T): T {
        runs += 1
        return delegate.run(block)
    }
}
