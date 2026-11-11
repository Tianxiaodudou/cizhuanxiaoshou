/*
 * @file fpk-server/note/src/main/kotlin/com/jiancai/note/NoteRepository.kt
 * @role `diff_note` 的唯一 SQL 出口（铁律 10）。
 * @exports （无）
 * @depends db-core, shared
 * @calledBy fpk-server/note/src/main/kotlin/com/jiancai/note/NoteService.kt
 * @sideEffects 写 diff_note（INSERT / UPDATE / DELETE）
 */

package com.jiancai.note

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.enums.DiffType

/**
 * 备注单持久化形态（内部类型）：只在 S10 内流转，对外一律转成 [NoteDto]（铁律 2）。
 */
internal data class StoredNote(
    val noteNo: String,
    val sourceOrderNo: String,
    val customerId: Long,
    val diffType: DiffType,
    val diffAmount: Money,
    val remark: String,
    val voided: Boolean,
    val createdAt: String,
    val createdBy: Long,
    val voidedAt: String?,
    val voidedBy: Long?,
    val voidReason: String?,
) {
    fun toDto(): NoteDto = NoteDto(
        noteNo = noteNo,
        sourceOrderNo = sourceOrderNo,
        customerId = customerId,
        diffType = diffType,
        diffAmount = diffAmount,
        remark = remark,
        voided = voided,
        createdAt = createdAt,
        createdBy = createdBy,
        voidedAt = voidedAt,
        voidedBy = voidedBy,
        voidReason = voidReason,
    )
}

/**
 * `diff_note` 的唯一 SQL 出口（铁律 10）。
 *
 * 本模块只拥有这一张表，且只提供「按单号读、插入、条件作废」三种语句：
 * 没有 DELETE，保证备注单只能作废、不能删除；也没有任何订单表的 SQL（铁律 3）。
 * 所有方法都要求调用方已在 `TxRunner.run {}` 事务内（Port 负责）。
 */
internal class NoteRepository {

    /** 按备注单号读取；不存在返回 null。 */
    fun findByNoteNo(tx: TxContext, noteNo: String): StoredNote? =
        tx.connection.prepareStatement(SELECT_BY_NOTE_NO).use { statement ->
            statement.setString(1, noteNo)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toNote() else null }
        }

    /** 插入一张备注单（单号为 S07 产出，本类不生成）。 */
    fun insert(tx: TxContext, note: StoredNote) {
        tx.connection.prepareStatement(INSERT_NOTE).use { statement ->
            statement.setString(1, note.noteNo)
            statement.setString(2, note.sourceOrderNo)
            statement.setLong(3, note.customerId)
            statement.setString(4, note.diffType.name)
            statement.setString(5, note.diffAmount.amount)
            statement.setString(6, note.remark)
            statement.setInt(7, if (note.voided) 1 else 0)
            statement.setString(8, note.createdAt)
            statement.setLong(9, note.createdBy)
            statement.setString(10, note.voidedAt)
            if (note.voidedBy == null) statement.setNull(11, java.sql.Types.INTEGER) else statement.setLong(11, note.voidedBy)
            statement.setString(12, note.voidReason)
            statement.executeUpdate()
        }
    }

    /**
     * 条件作废：`WHERE note_no = ? AND voided = 0`。
     *
     * 并发作废时只有第一个请求能更新成功；返回 false 由 Service 抛 `E4002`。
     * 只更新作废留痕列，差异数据与单号保持不变。
     */
    fun void(tx: TxContext, noteNo: String, voidedAt: String, voidedBy: Long, voidReason: String): Boolean =
        tx.connection.prepareStatement(VOID_NOTE).use { statement ->
            statement.setString(1, voidedAt)
            statement.setLong(2, voidedBy)
            statement.setString(3, voidReason)
            statement.setString(4, noteNo)
            statement.executeUpdate() > 0
        }

    private fun java.sql.ResultSet.toNote(): StoredNote = StoredNote(
        noteNo = getString("note_no"),
        sourceOrderNo = getString("source_order_no"),
        customerId = getLong("customer_id"),
        diffType = DiffType.valueOf(getString("diff_type")),
        diffAmount = Money.ofPlain(getString("diff_amount")),
        remark = getString("remark"),
        voided = getInt("voided") != 0,
        createdAt = getString("created_at"),
        createdBy = getLong("created_by"),
        voidedAt = getString("voided_at"),
        voidedBy = getLongOrNull("voided_by"),
        voidReason = getString("void_reason"),
    )

    /** `getLong` 对 NULL 返回 0，不能区分「未作废」与「id=0」，这里显式判空。 */
    private fun java.sql.ResultSet.getLongOrNull(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private companion object {
        const val NOTE_COLUMNS =
            "note_no, source_order_no, customer_id, diff_type, diff_amount, remark, voided, " +
                "created_at, created_by, voided_at, voided_by, void_reason"

        const val SELECT_BY_NOTE_NO = "SELECT $NOTE_COLUMNS FROM diff_note WHERE note_no = ?"

        const val INSERT_NOTE =
            "INSERT INTO diff_note " +
                "(note_no, source_order_no, customer_id, diff_type, diff_amount, remark, voided, " +
                "created_at, created_by, voided_at, voided_by, void_reason) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        const val VOID_NOTE =
            "UPDATE diff_note SET voided = 1, voided_at = ?, voided_by = ?, void_reason = ? " +
                "WHERE note_no = ? AND voided = 0"
    }
}
