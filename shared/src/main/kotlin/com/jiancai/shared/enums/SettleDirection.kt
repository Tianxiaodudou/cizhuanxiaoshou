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
