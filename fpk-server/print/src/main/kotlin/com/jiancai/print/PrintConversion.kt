/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   34 行  对象 PrintConversion
 * 第   37 行  类 Result
 * 第   45 行  函数 of
 * 第   77 行  函数 parseDecimal
 * 第   81 行  函数 format
 */

package com.jiancai.print

import com.jiancai.shared.dto.LineDto
import com.jiancai.shared.units.UnitDimension
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 打印用的装箱换算（只在服务端做，客户端零换算）。
 *
 * 数据源是订单明细里保存的装箱规格快照（`LineDto.packingSpec`），不重新读 S05 型号库，
 * 保证补打旧单时仍然按**下单当时**的规格显示。
 *
 * 口径：
 * - 仅当 `packingSpec.baseUnit == line.unit` 且单位是计数型（件 / 箱 / 袋）时换算；
 *   重量 / 面积 / 长度型单位的包装换算没有契约定义（[com.jiancai.shared.units.PackingSpec.unitsPerPackage]
 *   是整数，表达不了 1.44㎡/箱 这类规格），本模块不猜测，直接不显示；
 * - 包装数 = 数量 ÷ `unitsPerPackage`；整除显示 `2箱`，有零头显示 `2箱+5件`，
 *   不足一箱显示 `0.5箱`；
 * - 重量 / 体积 = 包装数 × 每包装重量 / 体积，最多 3 位小数、去掉尾零。
 *
 * 本换算只用于打印展示，**不回写订单金额**（铁律 5：订单金额由 S06 / S08 产出）。
 */
internal object PrintConversion {

    /** 一条明细的换算结果；不适用的字段为 null。 */
    data class Result(
        val packageText: String?,
        val weightKg: String?,
        val volumeM3: String?,
    )

    private val NONE = Result(null, null, null)

    fun of(line: LineDto): Result {
        val spec = line.packingSpec ?: return NONE
        if (spec.baseUnit != line.unit) return NONE
        if (spec.baseUnit.dimension != UnitDimension.COUNT) return NONE

        val quantity = parseDecimal(line.quantity)?.takeIf { it.signum() > 0 } ?: return NONE
        val unitsPerPackage = spec.unitsPerPackage?.takeIf { it > 0 } ?: return NONE
        val packageUnit = spec.packageUnit ?: return NONE

        val perPackage = BigDecimal(unitsPerPackage)
        val wholePackages = quantity.divideToIntegralValue(perPackage)
        val remainder = quantity.subtract(wholePackages.multiply(perPackage))

        val packageText = when {
            remainder.signum() == 0 -> "${format(wholePackages)}${packageUnit.label}"
            wholePackages.signum() == 0 ->
                "${format(quantity.divide(perPackage, 3, RoundingMode.HALF_UP))}${packageUnit.label}"
            else -> "${format(wholePackages)}${packageUnit.label}" +
                "+${format(remainder)}${line.unit.label}"
        }

        val packages = quantity.divide(perPackage, 6, RoundingMode.HALF_UP)
        val weight = spec.weightPerPackageKg
            ?.let(::parseDecimal)
            ?.let { perPackageWeight -> format(packages.multiply(perPackageWeight)) }
        val volume = spec.volumePerPackageM3
            ?.let(::parseDecimal)
            ?.let { perPackageVolume -> format(packages.multiply(perPackageVolume)) }

        return Result(packageText, weight, volume)
    }

    private fun parseDecimal(text: String): BigDecimal? =
        runCatching { BigDecimal(text.trim()) }.getOrNull()?.takeIf { it.signum() >= 0 }

    /** 最多 3 位小数，去掉尾零（`26.250` → `26.25`）。 */
    private fun format(value: BigDecimal): String =
        value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
