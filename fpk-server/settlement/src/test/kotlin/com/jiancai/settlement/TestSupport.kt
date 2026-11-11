/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第  117 行  函数 clockAt
 * 第  121 行  函数 testDb
 * 第  131 行  函数 settlementOnlyDb
 * 第  139 行  类 InMemoryAccountStore
 * 第  160 行  函数 list
 * 第  162 行  函数 get
 * 第  164 行  函数 create
 * 第  167 行  函数 update
 * 第  170 行  函数 delete
 * 第  175 行  类 SeededPurchase
 * 第  185 行  类 Fixture
 * 第  207 行  函数 customer
 * 第  214 行  函数 category
 * 第  238 行  函数 price
 * 第  256 行  函数 newOrder
 * 第  274 行  函数 seedPurchase
 * 第  288 行  函数 submit
 * 第  306 行  函数 approve
 * 第  310 行  函数 reject
 * 第  313 行  函数 balance
 * 第  316 行  函数 order
 * 第  321 行  函数 stored
 * 第  327 行  函数 settleRows
 * 第  329 行  函数 close
 * 第  333 行  函数 assertSettleError
 * 第  339 行  类 CountingTxRunner
 * 第  343 行  函数 reset
 * 第  347 行  函数 run
 * 第  354 行  函数 exec
 * 第  362 行  函数 queryLong
 * 第  369 行  函数 tableNames
 */

package com.jiancai.settlement

import com.jiancai.account.AccountAdminService
import com.jiancai.account.AccountStore
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
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.AuditSettleCmd
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.ChangePriceCmd
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.ReceivableDto
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.dto.SubmitSettleCmd
import com.jiancai.shared.dto.UpdateAccountCmd
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.enums.Role
import com.jiancai.shared.enums.SettleDirection
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/*
 * 测试夹具（不是业务代码）：内存库 + 固定时钟 + 真实 S04 / S05 / S06 / S07 / S08 / S03 实例。
 *
 * 生产 SQL 只允许出现在 SettleRepository（铁律 10）；这里的 SQL 仅用于断言库层约束、
 * 表归属与「事务回滚不留结算单 / 不消耗单号」。
 */

/** 服务端业务时区：与部署环境一致。 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 固定时钟的瞬间（UTC）：上海 2026-11-11 10:00。 */
internal const val NOW_INSTANT: String = "2026-11-11T02:00:00Z"

/** 固定时钟按服务端时区产出的线格式时间戳。 */
internal const val NOW_TIMESTAMP: String = "2026-11-11T10:00:00+08:00"

/** 提交人 id（S13 已认证后传入）；在 [InMemoryAccountStore] 里是启用账号。 */
internal const val OPERATOR_ID: Long = 9L

/** 审核人 id（S13 已认证的管理员）；本模块只校验账号存在，不判断角色。 */
internal const val AUDITOR_ID: Long = 7L

/** 不存在的账号 id。 */
internal const val UNKNOWN_ACCOUNT_ID: Long = 404L

/** 改价管理员 id（S13 已认证后传入 S06）。 */
internal const val ADMIN_ID: Long = 7L

internal fun clockAt(instant: String = NOW_INSTANT): Clock =
    Clock.fixed(Instant.parse(instant), TEST_ZONE)

/** 业务主链 + 单号 + 订单 + 结算的完整内存库，已迁移到最新版。 */
internal fun testDb(): DbCore = DbCore.testDatabase(
    SerialMigrations.all +
        CustomerMigrations.all +
        CategoryMigrations.all +
        PricingMigrations.all +
        OrderMigrations.all +
        SettlementMigrations.all,
)

/** 只含本模块迁移的内存库：用于断言 S11 只拥有 `settlement` 一张表。 */
internal fun settlementOnlyDb(): DbCore = DbCore.testDatabase(SettlementMigrations.all)

/**
 * S03 的账号存储替代实现（测试专用）。
 *
 * 只提供「账号存在」这一事实，用于验证 S11 真的经 `AccountAdminPort` 校验操作者；
 * 不实现密码、角色权限等 S02 职责。
 */
internal class InMemoryAccountStore : AccountStore {

    private val rows = linkedMapOf<Long, AccountDto>(
        OPERATOR_ID to AccountDto(
            id = OPERATOR_ID,
            username = "operator",
            displayName = "操作员",
            role = Role.STAFF,
            enabled = true,
            createdAt = NOW_TIMESTAMP,
        ),
        AUDITOR_ID to AccountDto(
            id = AUDITOR_ID,
            username = "admin",
            displayName = "管理员",
            role = Role.ADMIN,
            enabled = true,
            createdAt = NOW_TIMESTAMP,
        ),
    )

    override fun list(tx: TxContext): List<AccountDto> = rows.values.toList()

    override fun get(tx: TxContext, id: Long): AccountDto? = rows[id]

    override fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto =
        error("测试桩不实现创建账号")

    override fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto? =
        error("测试桩不实现修改账号")

    override fun delete(tx: TxContext, id: Long): Boolean =
        error("测试桩不实现删除账号")
}

/** 一键铺好的采购场景：客户 + 型号 + 价格 + 一张 24.60 的采购单。 */
internal data class SeededPurchase(
    val customerId: Long,
    val customerName: String,
    val categoryId: Long,
    val orderNo: String,
)

/**
 * 业务夹具：真实 S04 / S05 / S06 / S07 / S08 / S03 / S11 实例，共享同一个内存库与固定时钟。
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
    val accounts = AccountAdminService(txRunner, InMemoryAccountStore())
    val settles: SettlePort = SettleService(txRunner, orders, accounts, serial, clock)

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

    /** 建单便捷方法（默认 2 吨 / A 批号 / 采购单，金额 24.60）。 */
    fun newOrder(
        customerId: Long,
        categoryId: Long,
        quantity: String = "2",
        orderType: OrderType = OrderType.PURCHASE,
        requestId: String = "order-req-1",
    ): OrderDto = orders.create(
        CreateOrderCmd(
            clientRequestId = requestId,
            orderType = orderType,
            customerId = customerId,
            lines = listOf(OrderLineCmd(categoryId, "A", PricingUnit.TON, quantity)),
            remark = null,
        ),
        OPERATOR_ID,
    )

    /** 一键铺好的采购场景：客户 + 型号 + 价格 + 一张 24.60 的采购单。 */
    fun seedPurchase(): SeededPurchase {
        val customer = customer()
        val category = category()
        price(customer.id, category.id)
        val order = newOrder(customer.id, category.id)
        return SeededPurchase(
            customerId = customer.id,
            customerName = customer.name,
            categoryId = category.id,
            orderNo = order.orderNo,
        )
    }

    /** 提交一笔结算。 */
    fun submit(        orderNo: String,
        amount: String,
        direction: SettleDirection = SettleDirection.RECEIPT,
        requestId: String = "settle-req-1",
        remark: String? = null,
        submittedBy: Long = OPERATOR_ID,
    ): SettleDto = settles.submit(
        SubmitSettleCmd(
            clientRequestId = requestId,
            orderNo = orderNo,
            direction = direction,
            amount = Money.ofPlain(amount),
            remark = remark,
        ),
        submittedBy,
    )

    /** 审核通过。 */
    fun approve(settleNo: String, remark: String? = null, auditedBy: Long = AUDITOR_ID): SettleDto =
        settles.audit(AuditSettleCmd(settleNo, approved = true, remark = remark), auditedBy)

    /** 审核驳回。 */
    fun reject(settleNo: String, remark: String? = null, auditedBy: Long = AUDITOR_ID): SettleDto =
        settles.audit(AuditSettleCmd(settleNo, approved = false, remark = remark), auditedBy)

    fun balance(customerId: Long): ReceivableDto = settles.getBalance(customerId)

    /** 按单号读订单读模型（用于断言订单侧金额 / 状态未被结算改动）。 */
    fun order(orderNo: String): OrderDto =
        orders.getRecent(OrderQuery(orderNo = orderNo, pageSize = 1)).items.firstOrNull()
            ?: error("订单不存在：$orderNo")

    /** 直接读结算记录持久化形态（测试断言用；生产代码不这么访问）。 */
    fun stored(settleNo: String): StoredSettle =
        txRunner.run { tx ->
            SettleRepository().findBySettleNo(tx, settleNo) ?: error("结算单不存在：$settleNo")
        }

    /** 结算记录行数（测试断言用）。 */
    fun settleRows(): Long = txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM settlement") }

    override fun close() = db.close()
}

/** 断言结算错误码（S13 按 code 映射 HTTP，本模块测试只关心 code）。 */
internal fun assertSettleError(code: ErrorCode, block: () -> Unit) {
    val failure = assertFailsWith<SettleException> { block() }
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
