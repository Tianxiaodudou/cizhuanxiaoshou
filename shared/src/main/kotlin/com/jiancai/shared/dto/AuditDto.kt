/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   32 行  类 AuditEntryDto
 */

package com.jiancai.shared.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 审计留痕条目（1.2.0 追加，S14 observability 产出、S13 只读转发、管理端只展示）。
 *
 * 为什么 `action` 用字符串而不是枚举：服务端追加新动作时，旧客户端不会反序列化失败
 * （与 `ErrorResponse.code` 同一套前向兼容口径）。
 *
 * 金额仍以服务端产出的十进制字符串存在于 [details]（如 `old_price` / `new_price`），
 * 客户端只展示，不得解析成数字参与计算（铁律 5）。
 *
 * @property id 自增序号（严格递增，可当时间序）
 * @property action 动作名：`PRICE_CHANGE` / `SETTLE_AUDIT`（服务端内部枚举名）
 * @property targetType 目标类型：`price` / `settlement`
 * @property targetNo 业务主键：改价 = `客户id/型号id/批号`；审核 = 结算单号
 * @property operatorId 操作者账号 id（S13 认证后传入，仅留痕）
 * @property operatorLabel 操作者显示名快照
 * @property summary 一行摘要
 * @property details 明细键值（服务端产出）
 * @property createdAt 留痕时间（`WireFormat.TIMESTAMP`）
 * @property traceId 网关追踪 id
 */
@Serializable
data class AuditEntryDto(
    @SerialName("id") val id: Long,
    @SerialName("action") val action: String,
    @SerialName("target_type") val targetType: String,
    @SerialName("target_no") val targetNo: String,
    @SerialName("operator_id") val operatorId: Long,
    @SerialName("operator_label") val operatorLabel: String? = null,
    @SerialName("summary") val summary: String? = null,
    @SerialName("details") val details: Map<String, String> = emptyMap(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("trace_id") val traceId: String? = null,
)
