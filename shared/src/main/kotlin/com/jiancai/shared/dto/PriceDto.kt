/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   21 行  类 PriceDto
 * 第   35 行  类 ChangePriceCmd
 * 第   49 行  类 PriceChangeLog
 */

package com.jiancai.shared.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 恒定价格（唯一出参 DTO，S06 pricing 产出）。
 *
 * 维度：客户 + 型号 + 批号类型。当日可改、跨日锁定；
 * [locked] 由服务端按日期判定，客户端只据此把输入框设为只读，不得自行比较日期。
 */
@Serializable
data class PriceDto(
    @SerialName("id") val id: Long,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("batch_tag") val batchTag: String,
    @SerialName("unit_price") val unitPrice: Money,
    @SerialName("effective_date") val effectiveDate: String,
    @SerialName("locked") val locked: Boolean,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("updated_by") val updatedBy: Long,
)

/** 管理员改价入参（S06，权限由 S13 拦截，改价必须留痕）。 */
@Serializable
data class ChangePriceCmd(
    @SerialName("customer_id") val customerId: Long,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("batch_tag") val batchTag: String,
    @SerialName("new_price") val newPrice: Money,
    @SerialName("reason") val reason: String,
)

/**
 * 改价留痕（S06 产出，S14 审计消费）。
 *
 * 名称按模块方案约定保留 `PriceChangeLog`（不带 Dto 后缀），两端引用同一类型。
 */
@Serializable
data class PriceChangeLog(
    @SerialName("id") val id: Long,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("batch_tag") val batchTag: String,
    @SerialName("old_price") val oldPrice: Money,
    @SerialName("new_price") val newPrice: Money,
    @SerialName("admin_id") val adminId: Long,
    @SerialName("reason") val reason: String,
    @SerialName("changed_at") val changedAt: String,
)
