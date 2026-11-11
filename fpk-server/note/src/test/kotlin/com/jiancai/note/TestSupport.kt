/*
 * @file fpk-server/note/src/test/kotlin/com/jiancai/note/TestSupport.kt
 * @role 改价管理员 id（S13 已认证后传入 S06）。
 * @exports （无）
 * @depends category, customer, db-core, order, pricing, serial, shared
 * @calledBy （无）
 * @sideEffects 读 diff_note, sqlite_master（SELECT）
 */

package com.jiancai.note

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
import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.enums.DiffType
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/*
 * 测试夹具（不是业务代码）：内存库 + 固定时钟 + 真实 S04 / S05 / S06 / S07 / S08 实例。
 *
 * 生产 SQL 只允许出现在 NoteRepository（铁律 10）；这里的 SQL 仅用于断言库层约束、
 * 表归属与「事务回滚不留备注单」。
 */

/** 服务端业务时区：与部署环境一致。 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 固定时钟的瞬间（UTC）：上海 2026-11-11 10:00。 */
internal const val NOW_INSTANT: String = "2026-11-11T02:00:00Z"

/** 次日同一时刻（UTC）。 */
internal const val TOMORROW_INSTANT: String = "2026-11-12T02:00:00Z"

/** 固定时钟下的服务端业务日期。 */
internal const val TODAY: String = "2026-11-11"

/** 固定时钟次日的服务端业务日期。 */
internal const val TOMORROW: String = "2026-11-12"

/** S07 单号里的年月日段（`yyyyMMdd`）。 */
internal const val SERIAL_TODAY: String = "20261111"

/** S07 单号里次日年月日段。 */
internal const val SERIAL_TOMORROW: String = "20261112"

/** 固定时钟按服务端时区产出的线格式时间戳。 */
internal const val NOW_TIMESTAMP: String = "2026-11-11T10:00:00+08:00"

/** 次日同一时刻的线格式时间戳。 */
internal const val TOMORROW_TIMESTAMP: String = "2026-11-12T10:00:00+08:00"

/** 备注单操作者 id（S13 已认证后传入）。 */
internal const val OPERATOR_ID: Long = 9L

/** 作废操作者 id（与创建者区分，便于断言留痕）。 */
internal const val VOIDER_ID: Long = 11L

/** 改价管理员 id（S13 已认证后传入 S06）。 */
internal const val ADMIN_ID: Long = 7L

internal fun clockAt(instant: String = NOW_INSTANT): Clock =
    Clock.fixed(Instant.parse(instant), TEST_ZONE)

/** 业务主链 + 单号 + 订单 + 备注单的完整内存库，已迁移到最新版。 */
internal fun testDb(): DbCore = DbCore.testDatabase(
    SerialMigrations.all +
        CustomerMigrations.all +
        CategoryMigrations.all +
        PricingMigrations.all +
        OrderMigrations.all +
        NoteMigrations.all,
)

/** 只含本模块迁移的内存库：用于断言 S10 只拥有 `diff_note`。 */
internal fun noteOnlyDb(): DbCore = DbCore.testDatabase(NoteMigrations.all)

/**
 * 业务夹具：真实 S04 / S05 / S06 / S07 / S08 / S10 实例，共享同一个内存库与固定时钟。
 *
 * `notesAt(...)` 用同一库、不同服务端日期新建 S10（以及对应日期的 S07），
 * 用于验证「非当天原单也能补备注」。
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
    val notes: NotePort = NoteService(txRunner, orders, serial, clock)

    /** 指定时刻的 S08 实例（同一库、同一批主数据），用于跨日场景。 */
    fun ordersAt(instant: String): OrderPort = OrderService(
        txRunner = txRunner,
        serials = serial,
        prices = pricing,
        customers = customers,
        categories = categories,
        clock = clockAt(instant),
    )

    /** 指定时刻的 S10 实例（同一库、同一订单数据），用于跨日补备注场景。 */
    fun notesAt(instant: String): NotePort {
        val fixed = clockAt(instant)
        return NoteService(
            txRunner = txRunner,
            orders = ordersAt(instant),
            serials = SerialService(txRunner, fixed),
            clock = fixed,
        )
    }

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

    /** 首次定价 / 当日改价（走真实 S06）。 */
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
        requestId: String = "req-1",
    ): OrderDto = orders.create(
        CreateOrderCmd(
            clientRequestId = requestId,
            orderType = OrderType.PURCHASE,
            customerId = customerId,
            lines = listOf(OrderLineCmd(categoryId, "A", PricingUnit.TON, quantity)),
            remark = null,
        ),
        OPERATOR_ID,
    )

    /** 创建备注单便捷方法。 */
    fun newNote(
        sourceOrderNo: String,
        diffType: DiffType = DiffType.PRICE,
        diffAmount: String = "12.30",
        remark: String = "到货差异",
    ): NoteDto = notes.create(
        CreateNoteCmd(
            sourceOrderNo = sourceOrderNo,
            diffType = diffType,
            diffAmount = Money.ofPlain(diffAmount),
            remark = remark,
        ),
        OPERATOR_ID,
    )

    /** 按单号读订阅读模型（用于断言原单未被改动）。 */
    fun order(orderNo: String): OrderDto =
        orders.getRecent(OrderQuery(pageSize = 200)).items.firstOrNull { it.orderNo == orderNo }
            ?: error("订单不存在：$orderNo")

    /** 直接读备注单持久化形态（测试断言用；生产代码不这么访问）。 */
    fun stored(noteNo: String): StoredNote =
        txRunner.run { tx ->
            NoteRepository().findByNoteNo(tx, noteNo) ?: error("备注单不存在：$noteNo")
        }

    /** 备注单行数（测试断言用）。 */
    fun noteRows(): Long = txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM diff_note") }

    override fun close() = db.close()
}

/** 断言备注单错误码（S13 按 code 映射 HTTP，本模块测试只关心 code）。 */
internal fun assertNoteError(code: ErrorCode, block: () -> Unit) {
    val failure = assertFailsWith<NoteException> { block() }
    assertEquals(code, failure.code, "期望 $code，实际 ${failure.code}：${failure.message}")
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
