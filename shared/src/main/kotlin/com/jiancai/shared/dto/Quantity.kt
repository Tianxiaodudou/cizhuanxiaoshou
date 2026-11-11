/*
 * @file shared/src/main/kotlin/com/jiancai/shared/dto/Quantity.kt
 * @role 数量（唯一真源口径）：十进制字符串，必须 > 0，最多 [WireFormat.QUANTITY_MAX_SCALE] 位小数；
 * @exports Quantity, QuantitySerializer
 * @depends （无）
 * @calledBy fpk-server/order/src/main/kotlin/com/jiancai/order/OrderService.kt, fpk-server/return-check/src/main/kotlin/com/jiancai/returncheck/ReturnRules.kt
 * @sideEffects 无（纯计算 / 转发）
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
