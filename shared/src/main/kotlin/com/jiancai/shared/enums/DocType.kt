/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   19 行  类 DocType
 */

package com.jiancai.shared.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 单据类型（唯一真源）。
 *
 * 覆盖全部 6 类单据：采购单 / 采购退货单 / 直送单 / 直送退货单 / 备注单 / 结算单。
 * 单号前缀不在这里，见 [com.jiancai.shared.serial.SerialType]；
 * 两端（fpk-server、android-app）禁止各自另写文案或线值字符串。
 */
@Serializable
enum class DocType(val label: String) {
    @SerialName("purchase")
    PURCHASE("采购单"),

    @SerialName("purchase_return")
    PURCHASE_RETURN("采购退货单"),

    @SerialName("direct")
    DIRECT("直送单"),

    @SerialName("direct_return")
    DIRECT_RETURN("直送退货单"),

    @SerialName("note")
    NOTE("备注单"),

    @SerialName("settlement")
    SETTLEMENT("结算单"),
}
