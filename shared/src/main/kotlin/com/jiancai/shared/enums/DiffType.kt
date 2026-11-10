package com.jiancai.shared.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 差异类型（唯一真源）：备注单（S10）的差异分类。
 *
 * 备注单只记录差异、关联原单，不改原单数据。
 */
@Serializable
enum class DiffType(val label: String) {
    @SerialName("price")
    PRICE("价格差异"),

    @SerialName("quantity")
    QUANTITY("数量差异"),

    @SerialName("freight")
    FREIGHT("运费差异"),

    @SerialName("other")
    OTHER("其他"),
}
