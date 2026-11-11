/*
 * @file shared/src/main/kotlin/com/jiancai/shared/units/UnitDimension.kt
 * @role 计量维度（唯一真源）：单位换算与打印列头共用。
 * @exports UnitDimension
 * @depends （无）
 * @calledBy shared/src/main/kotlin/com/jiancai/shared/units/PricingUnit.kt, fpk-server/print/src/main/kotlin/com/jiancai/print/PrintConversion.kt
 * @sideEffects 无（纯计算 / 转发）
 */

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
