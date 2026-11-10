package com.jiancai.shared.dto

import com.jiancai.shared.enums.DiffType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 差异备注单（唯一出参 DTO，S10 note 产出）。
 *
 * 备注单只关联原单、记录差异，不改原单数据；只能作废，不能删除。
 */
@Serializable
data class NoteDto(
    @SerialName("note_no") val noteNo: String,
    @SerialName("source_order_no") val sourceOrderNo: String,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("diff_type") val diffType: DiffType,
    @SerialName("diff_amount") val diffAmount: Money,
    @SerialName("remark") val remark: String,
    @SerialName("voided") val voided: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("created_by") val createdBy: Long,
    @SerialName("voided_at") val voidedAt: String? = null,
    @SerialName("voided_by") val voidedBy: Long? = null,
    @SerialName("void_reason") val voidReason: String? = null,
)

/** 创建备注单入参（S10；原单校验由 S10 通过 OrderPort 完成）。 */
@Serializable
data class CreateNoteCmd(
    @SerialName("source_order_no") val sourceOrderNo: String,
    @SerialName("diff_type") val diffType: DiffType,
    @SerialName("diff_amount") val diffAmount: Money,
    @SerialName("remark") val remark: String,
)

/** 作废备注单入参（S10，只作废不修改原单）。 */
@Serializable
data class VoidNoteCmd(
    @SerialName("note_no") val noteNo: String,
    @SerialName("reason") val reason: String,
)
