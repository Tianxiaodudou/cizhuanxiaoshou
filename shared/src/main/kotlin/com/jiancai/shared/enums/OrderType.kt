/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   18 行  类 OrderType
 */

package com.jiancai.shared.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 订单类型（唯一真源）。
 *
 * 对应客户端 C06 的 4 类录入页：`OrderEntryRoute(orderType)` 只传这一个枚举，
 * 4 个页面共用 `OrderFormComponent`，由服务端按 [docType] 决定单号前缀与业务规则。
 */
@Serializable
enum class OrderType(val label: String, val docType: DocType) {
    @SerialName("purchase")
    PURCHASE("采购", DocType.PURCHASE),

    @SerialName("purchase_return")
    PURCHASE_RETURN("采购退货", DocType.PURCHASE_RETURN),

    @SerialName("direct")
    DIRECT("直送", DocType.DIRECT),

    @SerialName("direct_return")
    DIRECT_RETURN("直送退货", DocType.DIRECT_RETURN),
}
