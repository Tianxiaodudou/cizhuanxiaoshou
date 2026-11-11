/*
 * @file shared/src/main/kotlin/com/jiancai/shared/enums/SettleStatus.kt
 * @role 结算单状态（唯一真源，服务端 S11 维护）。
 * @exports SettleStatus
 * @depends （无）
 * @calledBy shared/src/main/kotlin/com/jiancai/shared/dto/SettleDto.kt, fpk-server/observability/src/main/kotlin/com/jiancai/observability/AuditService.kt, fpk-server/settlement/src/main/kotlin/com/jiancai/settlement/SettleRepository.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.shared.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 结算单状态（唯一真源，服务端 S11 维护）。
 *
 * 提交后由服务端当场判定（SUBMITTED），管理员审核后进入 APPROVED / REJECTED；
 * 只有 APPROVED 的收款单才计入「已收」。
 */
@Serializable
enum class SettleStatus(val label: String) {
    @SerialName("submitted")
    SUBMITTED("待审核"),

    @SerialName("approved")
    APPROVED("已确认"),

    @SerialName("rejected")
    REJECTED("已驳回"),

    @SerialName("void")
    VOID("已作废"),
}
