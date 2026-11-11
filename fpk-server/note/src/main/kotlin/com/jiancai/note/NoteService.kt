/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   67 行  类 NoteService
 * 第   81 行  函数 create
 * 第   84 行  函数 create
 * 第  115 行  函数 void
 * 第  118 行  函数 void
 * 第  149 行  函数 findSourceOrder
 * 第  155 行  函数 nextNoteNo
 * 第  163 行  函数 normalizeIdentifier
 * 第  176 行  函数 normalizeRequiredText
 * 第  188 行  函数 now
 * 第  190 行  对象 ?
 */

package com.jiancai.note

import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.order.OrderException
import com.jiancai.order.OrderPort
import com.jiancai.serial.SerialException
import com.jiancai.serial.SerialPort
import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.VoidNoteCmd
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.enums.DocType
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.serial.SerialType
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * [NotePort] 的唯一实现：差异备注单用例层。
 *
 * 职责边界：
 * - **不改原单**：创建时只经 [OrderPort] 读原单（存在性 + 客户快照），此后不写任何订单数据；
 *   本类没有订单 SQL，也不依赖 S08 的 repository（铁律 3 / 10）；
 * - **只作废不删除**：作废只更新备注单自身的作废留痕列，差异内容与单号永久保留；
 * - **单号只由 S07 产出**：调用 `SerialPort.next(tx, SerialType.of(DocType.NOTE))`，
 *   本类没有任何前缀字面量，也不拼接 / 截取单号（铁律 8）；
 * - **不判权限**：构造参数与参数里都没有角色 / token；`createdBy` / `voidedBy` 只用于留痕，
 *   登录与权限只在 S13 api-gateway（铁律 7）。
 *
 * 原单读取口径（S08 尚未提供「按单号取订单」的 Port 方法）：
 * 1. 先查 `OrderPort.today(tx)`，覆盖「当天单当天备注」的常见路径；
 * 2. 非当天单先用 `OrderPort.getReceivable(tx, orderNo)` 做一次存在性校验，
 *    不存在的单号直接 `E4003`，不翻页；
 * 3. 再用 `OrderPort.getRecent(tx, OrderQuery(...))` 按页查找订单 DTO，取客户 id 快照。
 * 全程只经 Port，不触碰订单表；等 S08 按「接口只增不改」补充按单号读取方法后，
 * 可把第 2 / 3 步替换为一次 Port 调用（见 module.md 待确认项）。
 *
 * 事务（铁律 4）：便捷重载各开一个写事务；带 tx 重载直接复用调用方事务。
 * 创建失败时，S07 的取号账本与本模块的备注单一起回滚，重试不会留下空号。
 *
 * @param txRunner db-core 的事务 Port
 * @param orders S08 订单 Port（只读：校验原单 + 客户快照）
 * @param serials S07 单号 Port（备注单号的唯一来源）
 * @param clock 服务端时钟；测试注入固定时钟以断言时间戳
 */
class NoteService(
    private val txRunner: TxRunner,
    private val orders: OrderPort,
    private val serials: SerialPort,
    private val clock: Clock = Clock.systemDefaultZone(),
) : NotePort {

    private val repository = NoteRepository()

    /** 时间戳按共享契约 [WireFormat.TIMESTAMP] 产出（含时区偏移）。 */
    private val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP)

    // —— 创建（关联原单，不改原单）——

    override fun create(cmd: CreateNoteCmd, createdBy: Long): NoteDto =
        txRunner.run { tx -> create(tx, cmd, createdBy) }

    override fun create(tx: TxContext, cmd: CreateNoteCmd, createdBy: Long): NoteDto {
        if (createdBy <= 0) throw NoteException(ErrorCode.E422, "操作者 id 不合法：$createdBy")

        val sourceOrderNo = normalizeIdentifier(cmd.sourceOrderNo, "原订单号")
        val remark = normalizeRequiredText(cmd.remark, "备注")
        val diffAmount = Money.ofPlain(cmd.diffAmount.amount)

        // 只读校验原单：不存在时直接失败，不消耗 S07 的备注单序号。
        val sourceOrder = findSourceOrder(tx, sourceOrderNo)
            ?: throw NoteException(ErrorCode.E4003, "原订单不存在，无法创建备注单：$sourceOrderNo")

        val note = StoredNote(
            noteNo = nextNoteNo(tx),
            sourceOrderNo = sourceOrderNo,
            customerId = sourceOrder.customerId,
            diffType = cmd.diffType,
            diffAmount = diffAmount,
            remark = remark,
            voided = false,
            createdAt = now(),
            createdBy = createdBy,
            voidedAt = null,
            voidedBy = null,
            voidReason = null,
        )
        repository.insert(tx, note)
        return note.toDto()
    }

    // —— 作废（只改备注单自身，不动原单）——

    override fun void(cmd: VoidNoteCmd, voidedBy: Long): NoteDto =
        txRunner.run { tx -> void(tx, cmd, voidedBy) }

    override fun void(tx: TxContext, cmd: VoidNoteCmd, voidedBy: Long): NoteDto {
        if (voidedBy <= 0) throw NoteException(ErrorCode.E422, "操作者 id 不合法：$voidedBy")

        val noteNo = normalizeIdentifier(cmd.noteNo, "备注单号")
        val reason = normalizeRequiredText(cmd.reason, "作废原因")

        val existing = repository.findByNoteNo(tx, noteNo)
            ?: throw NoteException(ErrorCode.E4001, "备注单不存在：$noteNo")
        if (existing.voided) throw NoteException(ErrorCode.E4002, "备注单已作废：$noteNo")

        val voidedAt = now()
        // 条件更新兜底并发：两个请求同时作废时，只有一个能把 voided 从 0 改成 1。
        val updated = repository.void(tx, noteNo, voidedAt, voidedBy, reason)
        if (!updated) throw NoteException(ErrorCode.E4002, "备注单已作废：$noteNo")

        return existing.copy(
            voided = true,
            voidedAt = voidedAt,
            voidedBy = voidedBy,
            voidReason = reason,
        ).toDto()
    }

    // —— 原单读取（只经 S08 Port）——

    /**
     * 查找原单 DTO；不存在返回 null。
     *
     * 契约 1.2.0 起直接调用 S08 新增的只读 [OrderPort.get]（按单号精确读），
     * 不再「先 today 再按页翻历史」（旧实现是 S08 缺 `get` 时的兼容路径）。
     */
    private fun findSourceOrder(tx: TxContext, orderNo: String): OrderDto? =
        orders.get(tx, orderNo)

    // —— 取号 / 入参规范化 / 时钟 ——

    /** 备注单号只由 S07 产出；取号失败原样保留 S07 的错误码。 */
    private fun nextNoteNo(tx: TxContext): String =
        try {
            serials.next(tx, SerialType.of(DocType.NOTE))
        } catch (failure: SerialException) {
            throw NoteException(failure.code, failure.message ?: "备注单号生成失败", failure)
        }

    /** 单号类入参只做形状校验（长度 / 控制字符），前缀与格式由 S07 保证。 */
    private fun normalizeIdentifier(raw: String, label: String): String {
        val text = raw.trim()
        if (text.isEmpty()) throw NoteException(ErrorCode.E422, "$label 不能为空")
        if (text.length > MAX_IDENTIFIER_LENGTH) {
            throw NoteException(ErrorCode.E422, "$label 不能超过 $MAX_IDENTIFIER_LENGTH 个字符")
        }
        if (text.any { it.isISOControl() }) {
            throw NoteException(ErrorCode.E422, "$label 不能包含控制字符")
        }
        return text
    }

    /** 必填文本：裁剪后非空、≤ 500 字符、无控制字符。 */
    private fun normalizeRequiredText(raw: String, label: String): String {
        val text = raw.trim()
        if (text.isEmpty()) throw NoteException(ErrorCode.E422, "$label 不能为空")
        if (text.length > MAX_TEXT_LENGTH) {
            throw NoteException(ErrorCode.E422, "$label 不能超过 $MAX_TEXT_LENGTH 个字符")
        }
        if (text.any { it.isISOControl() }) {
            throw NoteException(ErrorCode.E422, "$label 不能包含控制字符")
        }
        return text
    }

    private fun now(): String = OffsetDateTime.now(clock).format(timestampFormat)

    private companion object {
        /** 单号类字段最大长度（与 S08 保持同一口径）。 */
        const val MAX_IDENTIFIER_LENGTH: Int = 32

        /** 备注 / 作废原因最大长度。 */
        const val MAX_TEXT_LENGTH: Int = 500

        /** 历史订单翻页上限：与 S08 `OrderPort.getRecent` 的单页上限一致。 */
        const val MAX_ORDER_PAGE_SIZE: Int = 200
    }
}
