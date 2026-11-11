/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   29 行  类 AuditFixture
 * 第   34 行  函数 close
 * 第   36 行  对象 ?
 * 第   41 行  函数 create
 * 第   47 行  函数 priceLog
 * 第   67 行  函数 settle
 */

package com.jiancai.observability

import com.jiancai.db.DbCore
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.PriceChangeLog
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.enums.SettleDirection
import com.jiancai.shared.enums.SettleStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * S14 测试夹具：内存库 + 固定时钟 + 一条改价留痕 / 一条审核留痕的样板数据。
 *
 * 只依赖 db-core 与 shared（本模块的依赖面），不引入任何业务模块夹具。
 */
internal class AuditFixture private constructor(
    val db: DbCore,
    val audit: AuditService,
) : AutoCloseable {

    override fun close() = db.close()

    companion object {
        /** 固定时钟：`2026-11-11T09:00:00+08:00`。 */
        val FIXED_CLOCK: Clock =
            Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneId.of("Asia/Shanghai"))

        fun create(): AuditFixture {
            val db = DbCore.testDatabase(ObservabilityMigrations.all)
            return AuditFixture(db, AuditService(db.txRunner, clock = FIXED_CLOCK))
        }

        /** 样板改价留痕（S06 的 `PriceChangeLog` 口径）。 */
        fun priceLog(
            customerId: Long = 11,
            categoryId: Long = 22,
            batchTag: String = "A1",
            oldPrice: String = "100.00",
            newPrice: String = "120.50",
            adminId: Long = 7,
        ): PriceChangeLog = PriceChangeLog(
            id = 99,
            customerId = customerId,
            categoryId = categoryId,
            batchTag = batchTag,
            oldPrice = Money.ofPlain(oldPrice),
            newPrice = Money.ofPlain(newPrice),
            adminId = adminId,
            reason = "客户议价",
            changedAt = "2026-11-11T08:30:00+08:00",
        )

        /** 样板审核留痕（S11 的 `SettleDto` 口径）。 */
        fun settle(
            settleNo: String = "JS202611110001",
            status: SettleStatus = SettleStatus.APPROVED,
            auditedBy: Long? = 9,
        ): SettleDto = SettleDto(
            settleNo = settleNo,
            orderNo = "PS202611110001",
            customerId = 11,
            customerName = "张三",
            direction = SettleDirection.RECEIPT,
            amount = Money.ofPlain("500.00"),
            status = status,
            submittedAt = "2026-11-11T08:00:00+08:00",
            submittedBy = 5,
            auditedAt = if (auditedBy == null) null else "2026-11-11T08:20:00+08:00",
            auditedBy = auditedBy,
            remark = "现金结清",
        )
    }
}
