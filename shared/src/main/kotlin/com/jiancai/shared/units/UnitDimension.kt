package com.jiancai.shared.units

/**
 * 计量维度（唯一真源）：单位换算与打印列头共用。
 */
enum class UnitDimension(val label: String) {
    COUNT("计数"),
    WEIGHT("重量"),
    AREA("面积"),
    LENGTH("长度"),
}
