/*
 * @file fpk-server/observability/src/main/kotlin/com/jiancai/observability/AuditModels.kt
 * @role 一条待留痕的审计事件（S14 内部入参，不跨端）。
 * @exports AuditEvent, AuditQuery, AuditRecordDto
 * @depends （无）
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayRoutes.kt, fpk-server/observability/src/main/kotlin/com/jiancai/observability/AuditPort.kt, fpk-server/observability/src/main/kotlin/com/jiancai/observability/AuditRepository.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.observability

/**
 * 一条待留痕的审计事件（S14 内部入参，不跨端）。
 *
 * 硬边界：
 * - 只描述「谁在什么时候对什么做了什么」，**不带业务判定**（是否允许改价 / 是否允许审核由 S06 / S11 决定）；
 * - [details] 是服务端产出的字符串键值，禁止放密码、token、完整客户隐私；
 * - [operatorId] 是 S13 已认证的操作者 id，仅用于留痕，不是权限判断。
 *
 * @property action 动作（决定 [AuditAction.targetType]）
 * @property targetNo 业务主键：改价用「客户/型号/批号」三元组，审核用结算单号
 * @property operatorId 操作者账号 id（> 0）
 * @property operatorLabel 操作者显示名快照（可选，账号改名不回填历史）
 * @property summary 一行摘要（可选，<= [MAX_SUMMARY_LENGTH]）
 * @property details 明细键值（可选，<= [MAX_DETAIL_ENTRIES] 项）
 * @property traceId 网关请求追踪 id（可选，排查用）
 */
data class AuditEvent(
    val action: AuditAction,
    val targetNo: String,
    val operatorId: Long,
    val operatorLabel: String? = null,
    val summary: String? = null,
    val details: Map<String, String> = emptyMap(),
    val traceId: String? = null,
) {
    companion object {
        /** 摘要长度上限。 */
        const val MAX_SUMMARY_LENGTH: Int = 200

        /** 明细项数上限。 */
        const val MAX_DETAIL_ENTRIES: Int = 32

        /** 单条明细键 / 值长度上限。 */
        const val MAX_DETAIL_LENGTH: Int = 200
    }
}

/**
 * 审计查询条件（S14 内部入参，不跨端）；全部条件按 AND 组合，按 id 倒序（最新在前）。
 *
 * @property action 只看某个动作；null = 不过滤
 * @property targetNo 只看某个业务主键；null = 不过滤
 * @property operatorId 只看某个操作者；null = 不过滤
 * @property limit 返回条数上限（1..[MAX_LIMIT]）
 */
data class AuditQuery(
    val action: AuditAction? = null,
    val targetNo: String? = null,
    val operatorId: Long? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        /** 默认返回条数。 */
        const val DEFAULT_LIMIT: Int = 100

        /** 单次查询上限。 */
        const val MAX_LIMIT: Int = 500
    }
}

/**
 * 审计留痕（S14 唯一出参 DTO，不暴露 `audit_log` 的持久化结构，铁律 2）。
 *
 * 服务端内部读模型：给 S13 排查 / 管理端「谁改了价、谁审了单」用；
 * 目前没有跨端审计页面，因此不进 `shared`（若 C11 增开审计页，再按契约只增不改追加 DTO）。
 *
 * @property id 自增序号（严格递增，可作时间序）
 * @property action 动作
 * @property targetType 目标类型（取 [AuditAction.targetType]）
 * @property targetNo 业务主键
 * @property operatorId 操作者账号 id
 * @property operatorLabel 操作者显示名快照
 * @property summary 一行摘要
 * @property details 明细键值（字符串，键值均为服务端产出）
 * @property createdAt 留痕时间（`WireFormat.TIMESTAMP`，服务端时钟）
 * @property traceId 网关请求追踪 id
 */
data class AuditRecordDto(
    val id: Long,
    val action: AuditAction,
    val targetType: String,
    val targetNo: String,
    val operatorId: Long,
    val operatorLabel: String?,
    val summary: String?,
    val details: Map<String, String>,
    val createdAt: String,
    val traceId: String?,
)
