/*
 * @file fpk-server/note/src/main/kotlin/com/jiancai/note/NotePort.kt
 * @role 差异备注单 Port：S10 唯一对外入口（创建、作废）。
 * @exports NotePort
 * @depends db-core, shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayDeps.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt, fpk-server/note/src/main/kotlin/com/jiancai/note/NoteService.kt
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.note

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.VoidNoteCmd

/**
 * 差异备注单 Port：S10 唯一对外入口（创建、作废）。
 *
 * 契约口径：
 * - 出参一律 `shared` 的 [NoteDto]，不暴露 `diff_note` 的持久化结构（铁律 2）；
 * - 每个方法都有带 [TxContext] 的重载：调用方把「备注单 + 结算 / 其它写入」放进同一个
 *   db-core 事务（铁律 4）；便捷重载内部走 `TxRunner.run {}`，不存在非事务写入；
 * - **只关联原单、不改原单**：创建时经 S08 `OrderPort` 校验原单并读取客户快照，之后不再写
 *   任何订单数据；本模块没有任何订单表的 SQL（铁律 3 / 10）；
 * - **单号只由 S07 产出**：备注单号调用 `SerialPort.next(tx, SerialType.of(DocType.NOTE))`，
 *   本模块不拼接 / 截取 / 猜测前缀与序号（铁律 8）；
 * - **不判权限**（铁律 7）：参数里没有 token / 角色；`createdBy` / `voidedBy` 是已由 S13
 *   认证的操作者 id，只用于留痕，不是权限判断。
 *
 * 业务不变量：
 * - 备注单创建后只可作废，不可删除、不可改差异内容；
 * - 作废是终态：已作废的备注单再次作废抛 `E4002`；
 * - 备注单号由服务端产出，客户端只展示；差异金额由客户端提交业务事实，服务端负责规范化与校验。
 */
interface NotePort {

    /**
     * 创建差异备注单（关联原单）。
     *
     * 服务端负责：校验原单存在（经 S08）、读取客户 id 快照、取备注单号、生成时间戳。
     * 不修改原单的任何字段，也不写订单表。
     *
     * @param createdBy 已由 S13 认证的操作者 id，仅用于 `created_by` 留痕；本模块不校验角色
     * @return 落库后的备注单
     * @throws NoteException `E4003` 原订单不存在；`E422` 入参格式非法；
     *   `E9001` 取号冲突（可重试，保留 S07 错误码）
     */
    fun create(tx: TxContext, cmd: CreateNoteCmd, createdBy: Long): NoteDto

    /** [create] 的便捷重载：内部开一个写事务。 */
    fun create(cmd: CreateNoteCmd, createdBy: Long): NoteDto

    /**
     * 作废差异备注单（只改备注单自身，不动原单）。
     *
     * 作废后保留全部原始差异数据与单号，仅追加作废时间、操作者与原因；不可删除。
     *
     * @param voidedBy 已由 S13 认证的操作者 id，仅用于 `voided_by` 留痕；本模块不校验角色
     * @return 作废后的备注单
     * @throws NoteException `E4001` 备注单不存在；`E4002` 备注单已作废；`E422` 入参格式非法
     */
    fun void(tx: TxContext, cmd: VoidNoteCmd, voidedBy: Long): NoteDto

    /** [void] 的便捷重载：内部开一个写事务。 */
    fun void(cmd: VoidNoteCmd, voidedBy: Long): NoteDto
}
