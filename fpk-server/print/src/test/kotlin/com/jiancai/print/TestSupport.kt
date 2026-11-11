/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第  108 行  函数 clockAt
 * 第  111 行  函数 testDb
 * 第  122 行  函数 printOnlyDb
 * 第  130 行  类 Fixture
 * 第  174 行  函数 printService
 * 第  184 行  函数 customer
 * 第  191 行  函数 category
 * 第  215 行  函数 price
 * 第  233 行  函数 newOrder
 * 第  252 行  函数 order
 * 第  257 行  函数 note
 * 第  286 行  函数 printRows
 * 第  290 行  函数 printedByOf
 * 第  298 行  函数 close
 * 第  302 行  函数 assertPrintError
 * 第  308 行  类 CountingTxRunner
 * 第  312 行  函数 reset
 * 第  316 行  函数 run
 * 第  323 行  函数 exec
 * 第  331 行  函数 queryLong
 * 第  338 行  函数 tableNames
 */

package com.jiancai.print

import com.jiancai.category.CategoryMigrations
import com.jiancai.category.CategoryPort
import com.jiancai.category.CategoryService
import com.jiancai.config.ConfigMigrations
import com.jiancai.config.ConfigPort
import com.jiancai.config.ConfigService
import com.jiancai.config.DeploymentConfig
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
 * 测试夹具（不是业务代码）：内存库 + 固定时钟 + 真实 S01 / S04 / S05 / S06 / S07 / S08 实例。
 *
 * 生产 SQL 只允许出现在 PrintRepository（铁律 10）；这里的 SQL 仅用于断言库层约束、
 * 表归属与「事务回滚不扣打印次数」。
 */

/** 服务端业务时区：与部署环境一致。 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 固定时钟的瞬间（UTC）：上海 2026-11-11 10:00。 */
internal const val NOW_INSTANT: String = "2026-11-11T02:00:00Z"

/** 固定时钟下的服务端业务日期。 */
internal const val TODAY: String = "2026-11-11"

/** 固定时钟按服务端时区产出的线格式时间戳。 */
internal const val NOW_TIMESTAMP: String = "2026-11-11T10:00:00+08:00"

/** 订单操作者 id（S13 已认证后传入 S08）。 */
internal const val OPERATOR_ID: Long = 9L

/** 打印操作者 id（S13 已认证后传入 S12）。 */
internal const val PRINTER_ID: Long = 42L

/** 备注单作废人 id（测试备注单用）。 */
internal const val VOIDER_ID: Long = 11L

/** 改价管理员 id（S13 已认证后传入 S06）。 */
internal const val ADMIN_ID: Long = 7L

/** 部署级商家名称（S01 测试值）。 */
internal const val MERCHANT_NAME: String = "建材商行"

internal fun clockAt(instant: String = NOW_INSTANT): Clock = Clock.fixed(Instant.parse(instant), TEST_ZONE)

/** 业务主链 + 配置 + 打印的完整内存库，已迁移到最新版。 */
internal fun testDb(): DbCore = DbCore.testDatabase(
    SerialMigrations.all +
        ConfigMigrations.all +
        CustomerMigrations.all +
        CategoryMigrations.all +
        PricingMigrations.all +
        OrderMigrations.all +
        PrintMigrations.all,
)

/** 只含本模块迁移的内存库：用于断言 S12 只拥有 `print_job`。 */
internal fun printOnlyDb(): DbCore = DbCore.testDatabase(PrintMigrations.all)

/**
 * 业务夹具：真实 S01 / S04 / S05 / S06 / S07 / S08 + 本模块实例，共享同一个内存库与固定时钟。
 *
 * 备注单读入是内存 map（S10 当前没有读 Port）；`note(...)` 先把 [NoteDto] 放进 map，
 * 再经 [NoteReadPort] 读出，模拟组合根将来的适配器。
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
    val orders: OrderPort = OrderService(
        txRunner = txRunner,
        serials = serial,
        prices = pricing,
        customers = customers,
        categories = categories,
        clock = clock,
    )
    val config: ConfigPort = ConfigService(
        txRunner = txRunner,
        deploymentConfig = DeploymentConfig.of(MERCHANT_NAME),
        clock = clock,
    )

    /** S12 消费侧的备注单只读来源（测试替身）。 */
    val noteStore: MutableMap<String, NoteDto> = mutableMapOf()
    val noteReader: NoteReadPort = NoteReadPort { _, noteNo -> noteStore[noteNo] }

    val print: PrintPort = PrintService(
        txRunner = txRunner,
        orders = orders,
        config = config,
        notes = noteReader,
        clock = clock,
    )

    /** 用同一库 / 同一批 Port 新建一个 S12 实例（用于验证次数持久化）。 */
    fun printService(maxPrints: Int = PrintPort.DEFAULT_MAX_PRINTS): PrintPort = PrintService(
        txRunner = txRunner,
        orders = orders,
        config = config,
        notes = noteReader,
        clock = clock,
        maxPrintsPerDocument = maxPrints,
    )

    /** 建一个客户（电话唯一，重复电话会幂等返回已有客户）。 */
    fun customer(
        phone: String = "13800000000",
        name: String = "张三",
        address: String? = "建材市场 1 号",
    ): CustomerDto = customers.create(CreateCustomerCmd(name = name, phone = phone, address = address))

    /** 建一个型号；默认吨计价、50 袋/吨、规格文本 50kg/袋。 */
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

    /** 建单便捷方法。 */
    fun newOrder(
        customerId: Long,
        categoryId: Long,
        quantity: String = "2",
        orderType: OrderType = OrderType.PURCHASE,
        requestId: String = "req-1",
        batchTag: String = "A",
        unit: PricingUnit = PricingUnit.TON,
    ): OrderDto = orders.create(
        CreateOrderCmd(
            clientRequestId = requestId,
            orderType = orderType,
            customerId = customerId,
            lines = listOf(OrderLineCmd(categoryId, batchTag, unit, quantity)),
        ),
        OPERATOR_ID,
    )

    /** 按单号读订单（用于断言打印不改订单）。 */
    fun order(orderNo: String): OrderDto =
        orders.getRecent(OrderQuery(orderNo = orderNo, pageSize = 1)).items.firstOrNull()
            ?: error("订单不存在：$orderNo")

    /** 构造并登记一张备注单（S10 的读替身）。 */
    fun note(
        order: OrderDto,
        noteNo: String = "BZ202611110001",
        sourceOrderNo: String = order.orderNo,
        diffType: DiffType = DiffType.QUANTITY,
        diffAmount: String = "-12.30",
        remark: String = "少 5 件",
        voided: Boolean = false,
        voidReason: String? = null,
    ): NoteDto {
        val value = NoteDto(
            noteNo = noteNo,
            sourceOrderNo = sourceOrderNo,
            customerId = order.customerId,
            diffType = diffType,
            diffAmount = Money.ofPlain(diffAmount),
            remark = remark,
            voided = voided,
            createdAt = NOW_TIMESTAMP,
            createdBy = OPERATOR_ID,
            voidedAt = if (voided) NOW_TIMESTAMP else null,
            voidedBy = if (voided) VOIDER_ID else null,
            voidReason = voidReason,
        )
        noteStore[noteNo] = value
        return value
    }

    /** 某单据的打印次数（测试断言用；生产代码只经 PrintPort）。 */
    fun printRows(docNo: String): Long =
        txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM print_job WHERE doc_no = ?", docNo) }

    /** 最近一次打印人（测试断言用）。 */
    fun printedByOf(docNo: String): Long? =
        txRunner.run { tx ->
            tx.queryLong(
                "SELECT printed_by FROM print_job WHERE doc_no = ? ORDER BY id DESC LIMIT 1",
                docNo,
            ).takeIf { it != 0L }
        }

    override fun close() = db.close()
}

/** 断言打印错误码（S13 按 code 映射 HTTP，本模块测试只关心 code）。 */
internal fun assertPrintError(code: ErrorCode, block: () -> Unit) {
    val failure = assertFailsWith<PrintException> { block() }
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
