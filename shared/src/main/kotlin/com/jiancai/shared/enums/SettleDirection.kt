/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   17 行  类 SettleDirection
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
