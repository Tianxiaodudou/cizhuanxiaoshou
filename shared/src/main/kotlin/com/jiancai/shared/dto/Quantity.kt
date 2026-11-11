/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   36 行  类 Quantity
 * 第   38 行  函数 display
 * 第   40 行  函数 equals
 * 第   42 行  函数 hashCode
 * 第   44 行  函数 toString
 * 第   46 行  对象 ?
 * 第   50 行  函数 ofPlain
 * 第   68 行  对象 QuantitySerializer
 * 第   72 行  函数 serialize
 * 第   76 行  函数 deserialize
 */

package com.jiancai.shared.dto

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 数量（唯一真源口径）：十进制字符串，必须 > 0，最多 [WireFormat.QUANTITY_MAX_SCALE] 位小数；
 * 归一化为去尾零的规范写法（`"1.500"` → `"1.5"`，`"10.0"` → `"10"`）。
 *
 * 现有 DTO 的 quantity 字段仍是 `String`（硬约束：字段签名只增不改），
 * 本类型供 S08 / S09 等模块做入参校验与归一，避免每个模块各写一套数字解析；
 * 服务端产出规范写法，客户端只展示、零计算（铁律 5）。
 */
@Serializable(with = QuantitySerializer::class)
class Quantity private constructor(val value: String) {
    /** 展示用：返回服务端产出的规范十进制字符串。 */
    fun display(): String = value

    override fun equals(other: Any?): Boolean = other is Quantity && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "Quantity($value)"

    companion object {
        private val PATTERN: Regex = Regex(WireFormat.QUANTITY_PATTERN)

        /** 唯一构造入口：校验并归一化；非法数量直接抛 IllegalArgumentException（契约层不吞错）。 */
        fun ofPlain(text: String): Quantity {
            val trimmed = text.trim()
            require(PATTERN.matches(trimmed)) { "数量格式非法（只允许十进制数字）：$text" }
            val parsed = BigDecimal(trimmed)
            require(parsed.signum() > 0) { "数量必须大于 0：$text" }
            val canonical = parsed.stripTrailingZeros()
            require(canonical.scale() <= WireFormat.QUANTITY_MAX_SCALE) {
                "数量最多 ${WireFormat.QUANTITY_MAX_SCALE} 位小数：$text"
            }
            return Quantity(canonical.toPlainString())
        }
    }
}

/**
 * Quantity 的显式线序列化器：写出规范十进制字符串；读入时强制 `Quantity.ofPlain` 校验并归一化。
 * 两端统一经 `ContractJson.json` 使用，禁止任何一端另写数量解析/归一逻辑。
 */
object QuantitySerializer : KSerializer<Quantity> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.jiancai.shared.dto.Quantity", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Quantity) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): Quantity = Quantity.ofPlain(decoder.decodeString())
}
