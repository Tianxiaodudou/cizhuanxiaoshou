/*
 * @file shared/src/main/kotlin/com/jiancai/shared/enums/OrderStatus.kt
 * @role 订单状态（唯一真源，服务端 S08 派生，客户端只展示）。
 * @exports OrderStatus
 * @depends （无）
 * @calledBy shared/src/main/kotlin/com/jiancai/shared/dto/OrderDto.kt, fpk-server/order/src/main/kotlin/com/jiancai/order/OrderMath.kt, fpk-server/order/src/main/kotlin/com/jiancai/order/OrderPriceRecalculator.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.shared.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 订单状态（唯一真源，服务端 S08 派生，客户端只展示）。
 *
 * 已收 / 未收金额同样由服务端派生（[com.jiancai.shared.dto.OrderDto]），
 * 客户端不得自行根据状态推算金额。
 */
@Serializable
enum class OrderStatus(val label: String) {
    @SerialName("created")
    CREATED("已下单"),

    @SerialName("partially_settled")
    PARTIALLY_SETTLED("部分已收"),

    @SerialName("settled")
    SETTLED("已结清"),

    @SerialName("void")
    VOID("已作废"),
}
