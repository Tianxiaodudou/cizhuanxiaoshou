/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   31 行  接口 SerialPort
 * 第   42 行  函数 next
 * 第   49 行  函数 next
 * 第   61 行  函数 nextNote
 * 第   67 行  函数 nextNote
 */

package com.jiancai.serial

import com.jiancai.db.TxContext
import com.jiancai.shared.serial.SerialType

/**
 * 单号 Port：S07 唯一对外入口（铁律 8：业务模块只能通过本 Port 取号，禁止自行拼单号）。
 *
 * 单号格式固定为「前缀(2) + 年(4) + 月日(4) + 序号(4)」，例如 `PS202611100001`；
 * 前缀只能来自 [SerialType]（共享契约的唯一真源），调用方禁止自行拼接、截断或猜测。
 * S08 订单用 `next` / `next(tx, type)`；S10 备注单用 `nextNote`（前缀 BZ）。
 *
 * 事务口径（铁律 4）：
 * - 跨模块写用 `next(tx, type)`，把取号与业务写入放进同一个 [TxContext]：
 *   业务回滚时序号与账本一起回滚，不会留下「号已用、单未建」的空号；
 * - 单次独立取号用 `next(type)`，它内部仍走 `TxRunner.run {}`，
 *   不存在「非事务取号」的实现路径。
 *
 * 实现保证（验收）：并发取号不重号；前缀与年月日由服务端时钟产出，客户端只展示。
 */
interface SerialPort {

    /**
     * 在调用方事务内取号（推荐；S08 建单时与订单写入同一个事务）。
     *
     * @param tx 由 db-core 的 `TxRunner.run {}` 产生的事务上下文
     * @param type 单据类型对应的前缀，经 `SerialType.of(docType)` 解析
     * @return 单号（纯值；不返回 Entity）
     * @throws SerialException 序号用尽，或唯一约束重试后仍冲突（错误码 E9001）
     * @throws java.sql.SQLException 数据库层故障（表缺失、连接关闭等），原样上抛由 S13 兜底
     */
    fun next(tx: TxContext, type: SerialType): String

    /**
     * 便捷取号：内部自开事务（`TxRunner.run {}`），适合不与业务写入同事务的独立取号。
     *
     * 需要与业务写入同事务时请用 `next(tx, type)`；本方法不会绕开事务。
     */
    fun next(type: SerialType): String

    /**
     * 备注单取号（BZ）：在调用方事务内生成，等价于 `next(tx, SerialType.NOTE)`。
     *
     * S10 创建备注单时使用本方法，禁止自行拼 `BZ` 前缀；与 `next(tx, NOTE)` 共用同一条序列。
     *
     * @param tx 由 db-core 的 `TxRunner.run {}` 产生的事务上下文
     * @return 备注单号，例如 `BZ202611100001`
     * @throws SerialException 序号用尽，或唯一约束重试后仍冲突（错误码 E9001）
     * @throws java.sql.SQLException 数据库层故障，原样上抛由 S13 兜底
     */
    fun nextNote(tx: TxContext): String

    /**
     * 便捷备注单取号：内部自开事务，等价于 `next(SerialType.NOTE)`；
     * 与备注单业务写入同事务时请用 `nextNote(tx)`。
     */
    fun nextNote(): String
}
