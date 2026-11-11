/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   24 行  类 SettleDto
 * 第   41 行  类 SubmitSettleCmd
 * 第   51 行  类 AuditSettleCmd
 * 第   63 行  类 ReceivableDto
 */

package com.jiancai.shared.dto

import com.jiancai.shared.enums.SettleDirection
import com.jiancai.shared.enums.SettleStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 结算单（唯一出参 DTO，S11 settlement 产出）。
 *
 * 金额、状态（含「当场判定」结果）由服务端产出，客户端只展示；
 * 审核由管理员在 C11 完成，权限由 S13 拦截。
 */
@Serializable
data class SettleDto(
    @SerialName("settle_no") val settleNo: String,
    @SerialName("order_no") val orderNo: String,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("customer_name") val customerName: String,
    @SerialName("direction") val direction: SettleDirection,
    @SerialName("amount") val amount: Money,
    @SerialName("status") val status: SettleStatus,
    @SerialName("submitted_at") val submittedAt: String,
    @SerialName("submitted_by") val submittedBy: Long,
    @SerialName("audited_at") val auditedAt: String? = null,
    @SerialName("audited_by") val auditedBy: Long? = null,
    @SerialName("remark") val remark: String? = null,
)

/** 结算提交入参（S11；[clientRequestId] 为幂等键，避免重复提交）。 */
@Serializable
data class SubmitSettleCmd(
    @SerialName(FIELD_CLIENT_REQUEST_ID) val clientRequestId: String,
    @SerialName("order_no") val orderNo: String,
    @SerialName("direction") val direction: SettleDirection,
    @SerialName("amount") val amount: Money,
    @SerialName("remark") val remark: String? = null,
)

/** 结算审核入参（S11，管理员审核）。 */
@Serializable
data class AuditSettleCmd(
    @SerialName("settle_no") val settleNo: String,
    @SerialName("approved") val approved: Boolean,
    @SerialName("remark") val remark: String? = null,
)

/**
 * 已收 / 未收读模型（S11 派生，客户端只展示）。
 *
 * 已收金额只统计审核通过的收款；改价联动后应收从 S08 重新读取，已收不动。
 */
@Serializable
data class ReceivableDto(
    @SerialName("customer_id") val customerId: Long,
    @SerialName("customer_name") val customerName: String,
    @SerialName("receivable_amount") val receivableAmount: Money,
    @SerialName("received_amount") val receivedAmount: Money,
    @SerialName("unreceived_amount") val unreceivedAmount: Money,
)
