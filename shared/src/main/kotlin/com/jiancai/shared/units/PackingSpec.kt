/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   25 行  类 PackingSpec
 */

package com.jiancai.shared.units

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 装箱规格（唯一真源结构）：每个型号一条，由 S05 category 维护。
 *
 * 换算（[PricingUnit] 之间、计价数量 ↔ 包装数量）只在服务端进行（S12 打印 / S08 金额），
 * 客户端只展示 [specText] 等字段，零换算、零计算。
 *
 * @property baseUnit 计价基准单位（该型号默认计价单位）
 * @property packageUnit 包装单位（箱 / 袋，可空表示无包装换算）
 * @property unitsPerPackage 每个包装单位含多少 baseUnit（正整数）
 * @property weightPerPackageKg 每包装重量（kg，十进制字符串，可空）
 * @property volumePerPackageM3 每包装体积（m³，十进制字符串，可空）
 * @property specText 规格描述（如 800×800），只用于展示
 */
@Serializable
data class PackingSpec(
    @SerialName("base_unit") val baseUnit: PricingUnit,
    @SerialName("package_unit") val packageUnit: PricingUnit? = null,
    @SerialName("units_per_package") val unitsPerPackage: Int? = null,
    @SerialName("weight_per_package_kg") val weightPerPackageKg: String? = null,
    @SerialName("volume_per_package_m3") val volumePerPackageM3: String? = null,
    @SerialName("spec_text") val specText: String? = null,
)
