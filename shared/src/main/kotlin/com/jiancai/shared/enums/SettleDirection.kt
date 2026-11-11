/*
 * @file shared/src/main/kotlin/com/jiancai/shared/enums/SettleDirection.kt
 * @role 结算方向（唯一真源）：收款 / 退款。
 * @exports SettleDirection
 * @depends （无）
 * @calledBy shared/src/main/kotlin/com/jiancai/shared/dto/SettleDto.kt, fpk-server/settlement/src/main/kotlin/com/jiancai/settlement/SettleMath.kt, fpk-server/settlement/src/main/kotlin/com/jiancai/settlement/SettleRepository.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.shared.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 结算方向（唯一真源）：收款 / 退款。
 *
 * 金额方向只由服务端按该枚举换算，客户端只展示服务端给出的金额字符串。
 */
@Serializable
enum class SettleDirection(val label: String) {
    @SerialName("receipt")
    RECEIPT("收款"),

    @SerialName("refund")
    REFUND("退款"),
}
