/*
 * @file fpk-server/settlement/src/main/kotlin/com/jiancai/settlement/SettlePort.kt
 * @role 结算 Port：S11 唯一对外入口（提交、审核、已收/未收读模型）。
 * @exports SettlePort
 * @depends db-core, shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayDeps.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt, fpk-server/settlement/src/main/kotlin/com/jiancai/settlement/SettleService.kt
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.settlement

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.AuditSettleCmd
import com.jiancai.shared.dto.ReceivableDto
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.dto.SubmitSettleCmd

/**
 * 结算 Port：S11 唯一对外入口（提交、审核、已收/未收读模型）。
 *
 * 契约口径：
 * - 出参一律 `shared` 的 [SettleDto] / [ReceivableDto]，不暴露 `settlement` 表的持久化结构（铁律 2）；
 * - 每个方法都有带 [TxContext] 的重载：调用方把「结算 + 其它写入」放进同一个 db-core 事务
 *   （铁律 4）；便捷重载内部走 `TxRunner.run {}`，不存在非事务写入；
 * - **应收只来自 S08**：提交与审核都调用 `OrderPort.getReceivable` 重新读取，绝不缓存、
 *   绝不读订单表（铁律 3 / 10）；
 * - **已收只来自本模块**：`received = Σ APPROVED 收款 − Σ APPROVED 退款`；
 *   本模块**永不改写订单的 `received_amount` / 状态**，订单侧金额保持 S08 的原始事实；
 * - **单号只来自 S07**：结算单号调用 `SerialPort.next(tx, SerialType.of(DocType.SETTLEMENT))`，
 *   本模块不拼接 / 截取 / 猜测前缀与序号（铁律 8）；
 * - **不判权限**（铁律 7）：参数里没有 token / 角色；`submittedBy` / `auditedBy` 是已由 S13
 *   认证的操作者 id，仅用于留痕与 S03 的账号存在性校验，不是权限判断。
 *
 * 当场判定（提交即校验）：
 * - 收款：`待结 = 应收 − 已收`，要求 `待结 > 0` 且 `金额 ≤ 待结`，否则 `E5002`；
 * - 退款：要求 `待结 < 0` 且 `金额 ≤ −待结`，否则 `E5002`；
 * - 审核通过时用**当时**的应收与已收再判一次，防止多笔待审结算叠加后超收。
 *
 * 状态机：`SUBMITTED → APPROVED | REJECTED`；只有 `APPROVED` 计入已收，
 * 已审核的单不可重复审核（`E5003`）。
 */
interface SettlePort {

    /**
     * 提交一笔结算（收款 / 退款，幂等）。
     *
     * 服务端负责：校验订单存在、从 S08 重新读取应收、计算待结余额、经 S07 取结算单号、
     * 生成时间戳与客户快照。金额必须为正数，方向由 [SubmitSettleCmd.direction] 表达。
     *
     * 幂等：同一 [SubmitSettleCmd.clientRequestId] 且载荷相同，返回**同一张结算单**，
     * 不重复取号；同一请求 id 换了金额 / 方向 / 订单则抛 `E5004`。
     *
     * @param submittedBy 已由 S13 认证的操作者 id，仅用于 `submitted_by` 留痕与 S03 账号存在性校验
     * @return 落库后的结算单（状态为 `SUBMITTED`，已由服务端当场判定金额合法）
     * @throws SettleException `E2001` 订单不存在；`E5002` 金额超过待结余额；
     *   `E5004` 幂等键载荷冲突；`E422` 入参格式非法；`E6005` 操作者账号不存在；
     *   `E9001` 取号冲突（可重试，保留 S07 错误码）
     */
    fun submit(tx: TxContext, cmd: SubmitSettleCmd, submittedBy: Long): SettleDto

    /** [submit] 的便捷重载：内部开一个写事务。 */
    fun submit(cmd: SubmitSettleCmd, submittedBy: Long): SettleDto

    /**
     * 审核结算单（管理员在 C11 操作，权限由 S13 拦截）。
     *
     * 只允许 `SUBMITTED` 的单被审核；通过时重新读取 S08 应收并重算待结余额，
     * 若此时已超收则拒绝（`E5002`）且不改变状态。审核备注与提交备注分开保存。
     *
     * @param auditedBy 已由 S13 认证的管理员 id，仅用于 `audited_by` 留痕与账号存在性校验
     * @return 审核后的结算单（`APPROVED` / `REJECTED`）
     * @throws SettleException `E5001` 结算单不存在；`E5002` 审核通过会超收；
     *   `E5003` 已审核 / 并发审核失败；`E422` 入参格式非法；`E6005` 操作者账号不存在
     */
    fun audit(tx: TxContext, cmd: AuditSettleCmd, auditedBy: Long): SettleDto

    /** [audit] 的便捷重载：内部开一个写事务。 */
    fun audit(cmd: AuditSettleCmd, auditedBy: Long): SettleDto

    /**
     * 已收 / 未收读模型（按客户汇总）。
     *
     * - 应收：经 S08 `OrderPort.getRecent` 重新读取该客户的全部订单后求和（作废订单计 `0.00`），
     *   **不缓存、不落库**；改价 / 补单后下一次读取自然更新；
     * - 已收：本模块 `APPROVED` 收款 − `APPROVED` 退款；
     * - 未收：应收 − 已收。
     *
     * @throws SettleException `E422` 客户 id 不合法
     */
    fun getBalance(tx: TxContext, customerId: Long): ReceivableDto

    /** [getBalance] 的便捷重载：内部开一个只读事务。 */
    fun getBalance(customerId: Long): ReceivableDto
}
