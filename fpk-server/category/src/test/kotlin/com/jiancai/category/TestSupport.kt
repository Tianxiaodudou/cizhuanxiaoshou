/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   38 行  函数 testDb
 * 第   41 行  函数 cmd
 * 第   62 行  类 CountingTxRunner
 * 第   66 行  函数 run
 * 第   73 行  函数 queryLong
 */

package com.jiancai.category

import com.jiancai.db.DbCore
import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/*
 * 测试夹具（不是业务代码）：固定时钟 + 建库 + 少量断言用 SQL。
 * 生产 SQL 只允许出现在 CategoryRepository（铁律 10）；这里只做计数断言。
 */

/** 业务时区：与部署环境一致（服务端本地时间产出时间戳）。 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 固定时钟：UTC `02:00Z` = 上海 `10:00+08:00`，用于断言服务端产出的时间戳。 */
internal val TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-10T02:00:00Z"), TEST_ZONE)

/** 固定时钟下 `created_at` / `updated_at` 的期望值（共享契约的 [WireFormat.TIMESTAMP] 口径）。 */
internal const val CREATED_AT: String = "2026-11-10T10:00:00+08:00"

/** 打开已迁移的内存库（每个用例独立，天然隔离）。 */
internal fun testDb(): DbCore = DbCore.testDatabase(CategoryMigrations.all)

/** 构造型号库入参；id 为空表示新建，非空表示修改。 */
internal fun cmd(
    categoryName: String,
    model: String,
    unit: PricingUnit = PricingUnit.PIECE,
    batchTagRequired: Boolean = false,
    batchTags: List<String> = emptyList(),
    packingSpec: PackingSpec? = null,
    enabled: Boolean = true,
    id: Long? = null,
): CategoryCmd = CategoryCmd(
    id = id,
    categoryName = categoryName,
    model = model,
    unit = unit,
    batchTagRequired = batchTagRequired,
    batchTags = batchTags,
    packingSpec = packingSpec,
    enabled = enabled,
)

/** 事务计数包装：验证便捷重载确实各开一个事务、带 tx 重载不再自开事务。 */
internal class CountingTxRunner(private val delegate: TxRunner) : TxRunner {
    var runs: Int = 0
        private set

    override fun <T> run(block: (TxContext) -> T): T {
        runs += 1
        return delegate.run(block)
    }
}

/** 测试断言用计数查询（生产代码不出现）。 */
internal fun TxContext.queryLong(sql: String): Long =
    connection.prepareStatement(sql).use { statement ->
        statement.executeQuery().use { rows ->
            check(rows.next()) { "COUNT 查询必须返回一行：$sql" }
            rows.getLong(1)
        }
    }
