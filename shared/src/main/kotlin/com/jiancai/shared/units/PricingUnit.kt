/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   18 行  类 PricingUnit
 */

package com.jiancai.shared.units

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 计价单位（唯一真源）：件 / 箱 / 袋 / 吨 / 平方 / 米。
 *
 * 品类型号（S05）选定计价单位后，订单明细、结算、打印均引用同一枚举；
 * 换算系数属于每个型号的 [PackingSpec]，不在枚举里写死，避免两端各写一套换算。
 */
@Serializable
enum class PricingUnit(val label: String, val dimension: UnitDimension) {
    @SerialName("piece")
    PIECE("件", UnitDimension.COUNT),

    @SerialName("box")
    BOX("箱", UnitDimension.COUNT),

    @SerialName("bag")
    BAG("袋", UnitDimension.COUNT),

    @SerialName("ton")
    TON("吨", UnitDimension.WEIGHT),

    @SerialName("square_meter")
    SQUARE_METER("平方", UnitDimension.AREA),

    @SerialName("meter")
    METER("米", UnitDimension.LENGTH),
}
