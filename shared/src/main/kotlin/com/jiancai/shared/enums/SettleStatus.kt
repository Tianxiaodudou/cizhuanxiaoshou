/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   18 行  类 SettleStatus
 */

package com.jiancai.shared.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 结算单状态（唯一真源，服务端 S11 维护）。
 *
 * 提交后由服务端当场判定（SUBMITTED），管理员审核后进入 APPROVED / REJECTED；
 * 只有 APPROVED 的收款单才计入「已收」。
 */
@Serializable
enum class SettleStatus(val label: String) {
    @SerialName("submitted")
    SUBMITTED("待审核"),

    @SerialName("approved")
    APPROVED("已确认"),

    @SerialName("rejected")
    REJECTED("已驳回"),

    @SerialName("void")
    VOID("已作废"),
}
