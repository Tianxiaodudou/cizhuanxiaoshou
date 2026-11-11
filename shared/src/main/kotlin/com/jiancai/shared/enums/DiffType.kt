/*
 * @file shared/src/main/kotlin/com/jiancai/shared/enums/DiffType.kt
 * @role 差异类型（唯一真源）：备注单（S10）的差异分类。
 * @exports DiffType
 * @depends （无）
 * @calledBy shared/src/main/kotlin/com/jiancai/shared/dto/NoteDto.kt, fpk-server/note/src/main/kotlin/com/jiancai/note/NoteRepository.kt, android-app/feature-note/src/main/kotlin/com/jiancai/android/note/NoteRoute.kt
 * @sideEffects 无（纯计算 / 转发）
 */

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
