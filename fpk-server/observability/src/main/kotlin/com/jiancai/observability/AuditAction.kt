/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   14 行  类 AuditAction
 */

package com.jiancai.observability

/**
 * 审计动作（S14 内部枚举，不跨端；线格式由 `shared` 的 DTO 决定，若将来需要审计查询页再入契约）。
 *
 * 只描述「发生了什么」，**不含判定结果**（铁律：S14 不参与业务决策）。
 * 追加新动作 = 追加枚举项（只增不改）。
 */
enum class AuditAction(
    /** 留痕目标类型（`audit_log.target_type` 的取值）。 */
    val targetType: String,
) {
    /** 管理员改价：S06 已产出 `PriceChangeLog`，本模块只做留痕副本。 */
    PRICE_CHANGE("price"),

    /** 结算审核（通过 / 驳回）：S11 已产出 `SettleDto`，本模块只做留痕副本。 */
    SETTLE_AUDIT("settlement"),
}
