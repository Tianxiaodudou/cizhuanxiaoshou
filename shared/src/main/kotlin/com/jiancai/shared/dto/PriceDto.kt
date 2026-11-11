/*
 * @file shared/src/main/kotlin/com/jiancai/shared/dto/PriceDto.kt
 * @role 恒定价格（唯一出参 DTO，S06 pricing 产出）。
 * @exports PriceDto, ChangePriceCmd, PriceChangeLog
 * @depends （无）
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayRoutes.kt, fpk-server/observability/src/main/kotlin/com/jiancai/observability/AuditPort.kt, fpk-server/observability/src/main/kotlin/com/jiancai/observability/AuditService.kt
 * @sideEffects 无（纯计算 / 转发）
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
