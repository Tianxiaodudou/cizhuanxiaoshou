/*
 * @file fpk-server/order/src/test/kotlin/com/jiancai/order/TestSupport.kt
 * @role 改价管理员 id（S13 已认证后传入 S06）。
 * @exports （无）
 * @depends category, customer, db-core, pricing, serial, shared
 * @calledBy （无）
 * @sideEffects 读 sqlite_master（SELECT）
 */

package com.jiancai.order

import com.jiancai.category.CategoryMigrations
import com.jiancai.category.CategoryPort
import com.jiancai.category.CategoryService
import com.jiancai.customer.CustomerMigrations
import com.jiancai.customer.CustomerPort
import com.jiancai.customer.CustomerService
import com.jiancai.db.DbCore
import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
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
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.UpdateOrderCmd
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/*
 * 测试夹具（不是业务代码）：内存库 + 固定时钟 + 真实 S04 / S05 / S06 / S07 实例。
 *
 * 生产 SQL 只允许出现在 OrderRepository（铁律 10）；这里的 SQL 仅用于断言库层约束、
 * 表归属与「事务回滚不留单」。
 */

/** 服务端业务时区：与部署环境一致。 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 固定时钟的瞬间（UTC，测试输入）：上海 2026-11-11 10:00。 */
internal const val NOW_INSTANT: String = "2026-11-11T02:00:00Z"

/** 次日同一时刻（UTC，测试输入）。 */
internal const val TOMORROW_INSTANT: String = "2026-11-12T02:00:00Z"

/** 固定时钟下的服务端业务日期。 */
internal const val TODAY: String = "2026-11-11"

/** 固定时钟次日的服务端业务日期。 */
internal const val TOMORROW: String = "2026-11-12"

/** 固定时钟按服务端时区产出的线格式时间戳。 */
internal const val NOW_TIMESTAMP: String = "2026-11-11T10:00:00+08:00"

/** 改价 / 建单的操作者 id（S13 已认证后传入）。 */
internal const val OPERATOR_ID: Long = 9L

/** 改价管理员 id（S13 已认证后传入 S06）。 */
internal const val ADMIN_ID: Long = 7L

internal fun clockAt(instant: String = NOW_INSTANT): Clock = Clock.fixed(Instant.parse(instant), TEST_ZONE)

/** 业务主链 + 单号 + 订单的完整内存库，已迁移到最新版。 */
internal fun testDb(): DbCore = DbCore.testDatabase(
    SerialMigrations.all +
        CustomerMigrations.all +
        CategoryMigrations.all +
        PricingMigrations.all +
        OrderMigrations.all,
)

/** 只含本模块迁移的内存库：用于断言 S08 只拥有两张表。 */
internal fun orderOnlyDb(): DbCore = DbCore.testDatabase(OrderMigrations.all)

/**
 * 业务夹具：真实 S04 / S05 / S06 / S07 / S08 实例，共享同一个内存库与固定时钟。
 *
 * `ordersAt(...)` 用同一库、不同服务端日期新建一个 S08 实例，用于验证「仅当日单可改」，
 * 不修改系统时间、不依赖测试执行时刻。
 */
internal class Fixture : java.io.Closeable {
    val db: DbCore = testDb()
    val txRunner: TxRunner = db.txRunner
    val clock: Clock = clockAt()
    val customers: CustomerPort = CustomerService(txRunner, clock)
    val categories: CategoryPort = CategoryService(txRunner, clock)

    /** S06 改价时需要 S08 的重算监听器；先建监听器再建 pricing，避免构造环。 */
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

    /** 指定时刻的 S08 实例（同一库、同一批主数据），用于跨日场景。 */
    fun ordersAt(instant: String): OrderPort = OrderService(
        txRunner = txRunner,
        serials = serial,
        prices = pricing,
        customers = customers,
        categories = categories,
        clock = clockAt(instant),
    )

    /** 建一个客户（电话唯一，重复电话会幂等返回已有客户）。 */
    fun customer(
        phone: String = "13800000000",
        name: String = "张三",
        address: String? = "建材市场 1 号",
    ): CustomerDto = customers.create(CreateCustomerCmd(name = name, phone = phone, address = address))

    /** 建一个型号；默认要求批号且可选 A / B（计价单位吨）。 */
    fun category(
        categoryName: String = "水泥",
        model: String = "P.O 42.5",
        unit: PricingUnit = PricingUnit.TON,
        batchTagRequired: Boolean = true,
        batchTags: List<String> = listOf("A", "B"),
        packingSpec: PackingSpec? = PackingSpec(
            baseUnit = PricingUnit.TON,
            packageUnit = PricingUnit.BAG,
            unitsPerPackage = 50,
            specText = "50kg/袋",
        ),
    ): CategoryDto = categories.create(
        CategoryCmd(
            categoryName = categoryName,
            model = model,
            unit = unit,
            batchTagRequired = batchTagRequired,
            batchTags = batchTags,
            packingSpec = packingSpec,
        ),
    )

    /** 首次定价 / 当日改价（走真实 S06；改价会触发 [recalculator] 重算当日订单）。 */
    fun price(
        customerId: Long,
        categoryId: Long,
        unitPrice: String = "12.30",
        batchTag: String = "A",
        reason: String = "调整价格",
    ) = pricing.changePrice(
        ChangePriceCmd(
            customerId = customerId,
            categoryId = categoryId,
            batchTag = batchTag,
            newPrice = Money.ofPlain(unitPrice),
            reason = reason,
        ),
        ADMIN_ID,
    )

    /** 建单便捷方法（默认 2 吨 / A 批号 / 采购单）。 */
    fun newOrder(
        customerId: Long,
        categoryId: Long,
        quantity: String = "2",
        orderType: OrderType = OrderType.PURCHASE,
        requestId: String = "req-1",
        batchTag: String = "A",
        unit: PricingUnit = PricingUnit.TON,
        sourceOrderNo: String? = null,
    ): OrderDto = orders.create(
        createCmd(
            customerId = customerId,
            categoryId = categoryId,
            quantity = quantity,
            orderType = orderType,
            requestId = requestId,
            batchTag = batchTag,
            unit = unit,
            sourceOrderNo = sourceOrderNo,
        ),
        OPERATOR_ID,
    )

    override fun close() = db.close()
}

/** 建单入参；`lines` 为空列表时用于「明细不能为空」的用例。 */
internal fun createCmd(
    customerId: Long,
    categoryId: Long,
    quantity: String = "2",
    orderType: OrderType = OrderType.PURCHASE,
    requestId: String = "req-1",
    batchTag: String = "A",
    unit: PricingUnit = PricingUnit.TON,
    remark: String? = null,
    lines: List<OrderLineCmd>? = null,
    sourceOrderNo: String? = null,
): CreateOrderCmd = CreateOrderCmd(
    clientRequestId = requestId,
    orderType = orderType,
    customerId = customerId,
    lines = lines ?: listOf(OrderLineCmd(categoryId, batchTag, unit, quantity)),
    remark = remark,
    sourceOrderNo = sourceOrderNo,
)

/** 改单入参：默认沿用订单首行的型号 / 单位 / 批号，只改数量。 */
internal fun updateCmd(
    order: OrderDto,
    quantity: String = "3",
    batchTag: String = "A",
    unit: PricingUnit = PricingUnit.TON,
    remark: String? = null,
    version: Long = order.version,
): UpdateOrderCmd = UpdateOrderCmd(
    orderNo = order.orderNo,
    version = version,
    lines = listOf(
        OrderLineCmd(
            categoryId = order.lines.first().categoryId,
            batchTag = batchTag,
            unit = unit,
            quantity = quantity,
        ),
    ),
    remark = remark,
)

/** 断言订单错误码（S13 按 code 映射 HTTP，本模块测试只关心 code）。 */
internal fun assertOrderError(code: com.jiancai.shared.errors.ErrorCode, block: () -> Unit) {
    val failure = kotlin.test.assertFailsWith<OrderException> { block() }
    kotlin.test.assertEquals(code, failure.code, "期望 $code，实际 ${failure.code}：${failure.message}")
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

/** 测试断言用写 SQL（生产代码不出现）。 */
internal fun TxContext.exec(sql: String, vararg args: Any?) {
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
        statement.executeUpdate()
    }
}

/** 测试断言用计数查询（生产代码不出现）。 */
internal fun TxContext.queryLong(sql: String, vararg args: Any?): Long =
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
        statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
    }

/** 测试断言用单值查询（生产代码不出现）。 */
internal fun TxContext.queryString(sql: String, vararg args: Any?): String? =
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

/** 测试断言用表名列表（生产代码不出现）。 */
internal fun TxContext.tableNames(): List<String> =
    connection.prepareStatement(
        "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT IN ('schema_migration', 'sqlite_sequence') ORDER BY name",
    ).use { statement ->
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }
    }
