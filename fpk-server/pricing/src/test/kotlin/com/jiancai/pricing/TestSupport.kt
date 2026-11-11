/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   74 行  函数 clockAt
 * 第   77 行  函数 testDb
 * 第   82 行  函数 pricingOnlyDb
 * 第   90 行  类 Fixture
 * 第  101 行  函数 pricingAt
 * 第  113 行  函数 customer
 * 第  119 行  函数 category
 * 第  135 行  函数 close
 * 第  139 行  函数 priceCmd
 * 第  154 行  类 RecordingListener
 * 第  157 行  函数 onPriceChanged
 * 第  163 行  类 CountingTxRunner
 * 第  167 行  函数 reset
 * 第  171 行  函数 run
 * 第  178 行  函数 exec
 * 第  186 行  函数 queryLong
 * 第  193 行  函数 queryString
 * 第  200 行  函数 tableNames
 */

package com.jiancai.pricing

import com.jiancai.category.CategoryMigrations
import com.jiancai.category.CategoryPort
import com.jiancai.category.CategoryService
import com.jiancai.customer.CustomerMigrations
import com.jiancai.customer.CustomerPort
import com.jiancai.customer.CustomerService
import com.jiancai.db.DbCore
import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.ChangePriceCmd
import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.units.PricingUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/*
 * 测试夹具（不是业务代码）：内存库 + 固定时钟 + 事务计数。
 *
 * 生产 SQL 只允许出现在 PriceRepository（铁律 10）；这里的 SQL 仅用于
 * 验证库层约束、留痕不可删改与「本模块只拥有两张表」。
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

/** 次日同一时刻的线格式时间戳。 */
internal const val TOMORROW_TIMESTAMP: String = "2026-11-12T10:00:00+08:00"

internal fun clockAt(instant: String = NOW_INSTANT): Clock = Clock.fixed(Instant.parse(instant), TEST_ZONE)

/** 业务主链三模块的完整内存库（客户 + 型号 + 定价），已迁移到最新版。 */
internal fun testDb(): DbCore = DbCore.testDatabase(
    CustomerMigrations.all + CategoryMigrations.all + PricingMigrations.all,
)

/** 只含本模块迁移的内存库：用于断言 S06 只拥有两张表。 */
internal fun pricingOnlyDb(): DbCore = DbCore.testDatabase(PricingMigrations.all)

/**
 * 业务夹具：真实 S04 / S05 / S06 实例，共享同一个内存库与固定时钟。
 *
 * `pricingAt(...)` 用同一库、同一批客户 / 型号、不同日期的时钟新建一个 S06 实例，
 * 用于验证跨日锁（不改系统时间、不依赖测试执行时刻）。
 */
internal class Fixture(
    private val listeners: List<PriceChangeListener> = emptyList(),
) : java.io.Closeable {
    val db: DbCore = testDb()
    val txRunner: TxRunner = db.txRunner
    val clock: Clock = clockAt()
    val customers: CustomerPort = CustomerService(txRunner, clock)
    val categories: CategoryPort = CategoryService(txRunner, clock)
    val pricing: PricePort = PriceService(txRunner, customers, categories, clock, listeners)

    /** 指定时刻的 S06 实例（同一库、同一批主数据），用于跨日场景。 */
    fun pricingAt(
        instant: String,
        extraListeners: List<PriceChangeListener> = emptyList(),
    ): PricePort = PriceService(
        txRunner = txRunner,
        customers = customers,
        categories = categories,
        clock = clockAt(instant),
        listeners = listeners + extraListeners,
    )

    /** 建一个客户（电话唯一，重复电话会幂等返回已有客户）。 */
    fun customer(
        phone: String = "13800000000",
        name: String = "张三",
    ): CustomerDto = customers.create(CreateCustomerCmd(name = name, phone = phone))

    /** 建一个型号；默认要求批号且可选 A / B。 */
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
        ),
    )

    override fun close() = db.close()
}

/** 改价入参（默认 A 批号、12.30 元）。 */
internal fun priceCmd(
    customerId: Long,
    categoryId: Long,
    batchTag: String = "A",
    newPrice: String = "12.30",
    reason: String = "调整价格",
): ChangePriceCmd = ChangePriceCmd(
    customerId = customerId,
    categoryId = categoryId,
    batchTag = batchTag,
    newPrice = Money.ofPlain(newPrice),
    reason = reason,
)

/** 记录 PriceChanged 的监听器（模拟 S08 重算入口）。 */
internal class RecordingListener : PriceChangeListener {
    val events: MutableList<PriceChanged> = mutableListOf()

    override fun onPriceChanged(event: PriceChanged) {
        events += event
    }
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
