package com.jiancai.shared.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 金额（唯一真源，线格式固定为十进制字符串，两位小数，如 `"123.45"`、`"0.00"`、`"-5.00"`）。
 *
 * 硬性规则（R5）：金额一律由服务端产出（S06 定价 / S08 订单 / S11 结算），
 * 客户端只展示 [amount] / [display]，不得解析成数字做任何计算。
 *
 * 不可变金额类型：主构造器私有，业务代码只能经 [ofPlain] 构造（校验 + 归一化为两位小数）；
 * 线格式由 [MoneySerializer] 显式固定为字符串，反序列化同样经 [ofPlain] 校验，不使用浮点数。
 */
@Serializable(with = MoneySerializer::class)
class Money private constructor(val amount: String) {
    /** 客户端展示用（返回服务端产出的规范十进制字符串）。 */
    fun display(): String = amount

    override fun equals(other: Any?): Boolean = other is Money && other.amount == amount

    override fun hashCode(): Int = amount.hashCode()

    override fun toString(): String = "Money($amount)"

    companion object {
        private val PATTERN: Regex = Regex("""^-?\d+(\.\d{1,2})?$""")

        /** 唯一构造入口：规范化为两位小数；非法格式直接抛 IllegalArgumentException（契约层不吞错）。 */
        fun ofPlain(text: String): Money = Money(normalize(text))

        private fun normalize(text: String): String {
            val trimmed = text.trim()
            require(PATTERN.matches(trimmed)) { "金额格式非法：$text" }
            val negative = trimmed.startsWith("-")
            val body = if (negative) trimmed.substring(1) else trimmed
            val dot = body.indexOf('.')
            val integerPart = (if (dot >= 0) body.substring(0, dot) else body)
                .trimStart('0')
                .ifEmpty { "0" }
            val decimalPart = if (dot >= 0) body.substring(dot + 1) else ""
            val normalized = "$integerPart.${decimalPart.padEnd(2, '0')}"
            return if (negative && normalized != "0.00") "-$normalized" else normalized
        }
    }
}

/**
 * Money 的显式线序列化器：写出十进制字符串；读入时强制 `Money.ofPlain` 校验并归一化。
 *
 * 两端统一经 `ContractJson.json` 使用，禁止任何一端另写金额序列化逻辑，
 * 也不允许出现浮点数形式的金额（避免精度丢失）。
 */
object MoneySerializer : KSerializer<Money> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.jiancai.shared.dto.Money", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Money) {
        encoder.encodeString(value.amount)
    }

    override fun deserialize(decoder: Decoder): Money = Money.ofPlain(decoder.decodeString())
}
