/*
 * @file fpk-server/observability/src/main/kotlin/com/jiancai/observability/AuditPort.kt
 * @role 审计 Port：S14 唯一对外入口（留痕写入 + 只读查询）。
 * @exports AuditPort
 * @depends db-core, shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayDeps.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt, fpk-server/observability/src/main/kotlin/com/jiancai/observability/AuditService.kt
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.observability

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.AuditEntryDto
import com.jiancai.shared.dto.PriceChangeLog
import com.jiancai.shared.dto.SettleDto

/**
 * 审计 Port：S14 唯一对外入口（留痕写入 + 只读查询）。
 *
 * 契约口径：
 * - **只追加**：没有 update / delete；整改靠追加新条目，历史不可篡改；
 * - **并回调用方事务**：带 [TxContext] 的重载让「业务写入 + 留痕」原子提交（铁律 4），
 *   业务回滚时留痕一并回滚，不会出现「单没改成却有改价记录」；便捷重载内部走 `TxRunner.run {}`；
 * - **不参与业务决策**：本 Port 从不返回「能否改价 / 能否审核」，只记录已发生的事实；
 *   调用方必须在业务成功后调用（S13 已把权限拦在网关，铁律 7）；
 * - **出参 DTO**：[AuditRecordDto]，不暴露 `audit_log` 持久化结构（铁律 2）；
 * - **不跨模块读写表**：只依赖 S00 db-core；改价 / 审核的输入用 `shared` 的
 *   [PriceChangeLog] / [SettleDto]，因此本模块不 import 任何业务模块（铁律 3）。
 *
 * 典型装配（组合根把同一个实例交给 S13）：
 * ```
 * val audit: AuditPort = AuditService(db.txRunner)
 * db.txRunner.run { tx ->
 *     val dto = prices.changePrice(tx, cmd, adminId)          // S06 业务写入
 *     audit.priceChanged(tx, prices.history(...).last())      // 同一事务留痕
 * }
 * ```
 */
interface AuditPort {

    /** 追加一条审计事件（带调用方事务）。 */
    fun record(tx: TxContext, event: AuditEvent): AuditRecordDto

    /** [record] 的便捷重载：内部开一个写事务。 */
    fun record(event: AuditEvent): AuditRecordDto

    /**
     * 记一次改价留痕（S06 产出的 `PriceChangeLog` 原样落副本）。
     *
     * 只记录 `PriceChangeLog` 已包含的事实（客户 / 型号 / 批号 / 旧价 / 新价 / 管理员 / 原因 / 时间），
     * 不重新计算价格（铁律 5）。
     */
    fun priceChanged(tx: TxContext, log: PriceChangeLog, traceId: String? = null): AuditRecordDto

    /** [priceChanged] 的便捷重载。 */
    fun priceChanged(log: PriceChangeLog, traceId: String? = null): AuditRecordDto

    /**
     * 记一次结算审核留痕（S11 产出的 `SettleDto` 原样落副本）。
     *
     * @param approved 审核结论（`true` 通过 / `false` 驳回）；由 S11 的 `SettleDto.status` 复核一致
     * @param remark 审核备注（可选，通常是 `AuditSettleCmd.remark`）
     */
    fun settleAudited(
        tx: TxContext,
        settle: SettleDto,
        approved: Boolean,
        remark: String? = null,
        traceId: String? = null,
    ): AuditRecordDto

    /** [settleAudited] 的便捷重载。 */
    fun settleAudited(
        settle: SettleDto,
        approved: Boolean,
        remark: String? = null,
        traceId: String? = null,
    ): AuditRecordDto

    /** 按条件查询留痕（带调用方事务）。 */
    fun query(tx: TxContext, filter: AuditQuery = AuditQuery()): List<AuditRecordDto>

    /** [query] 的便捷重载：内部开一个只读事务。 */
    fun query(filter: AuditQuery = AuditQuery()): List<AuditRecordDto>

    /**
     * 审计台账的**跨端读模型**（contractVersion 1.2.0 追加；S13 `GET /audit` 用）。
     *
     * 与 [query] 同一份数据，只是换成 `shared` 的 [AuditEntryDto]：
     * - 服务端内部仍用 [AuditRecordDto]（不受契约约束）；
     * - 出口只给两端共用类型，避免 S13 自己造 DTO（铁律 9）。
     */
    fun entries(tx: TxContext, filter: AuditQuery = AuditQuery()): List<AuditEntryDto>

    /** [entries] 的便捷重载：内部开一个只读事务。 */
    fun entries(filter: AuditQuery = AuditQuery()): List<AuditEntryDto>

    companion object {
        /** 改价留痕的业务主键口径：`客户id/型号id/批号`（批号可空，用 `-` 占位）。 */
        fun priceKey(customerId: Long, categoryId: Long, batchTag: String): String =
            "$customerId/$categoryId/${batchTag.ifBlank { "-" }}"
    }
}
