/*
 * @file fpk-server/observability/src/main/kotlin/com/jiancai/observability/AuditException.kt
 * @role 审计用例失败（S14）。
 * @exports AuditException
 * @depends shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ErrorMapping.kt, fpk-server/observability/src/main/kotlin/com/jiancai/observability/AuditService.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.observability

import com.jiancai.shared.errors.ErrorCode

/**
 * 审计用例失败（S14）。
 *
 * 错误码只取 `shared` 的 [ErrorCode]（唯一真源），S13 按 [code] 映射 HTTP 状态与统一响应体。
 * 本模块只用通用码（不新增业务段位）：
 * - `E422` 入参非法（动作 / 主键 / 操作者 / 摘要 / 明细 / 查询条件）；
 * - `E500` 审计数据损坏（`details` 无法还原）；
 * - `E503` 数据库不可用。
 *
 * `E403` 不会从本模块抛出：权限只在 S13 判断（铁律 7）。
 *
 * @property code 共享契约中的错误码
 */
class AuditException(
    val code: ErrorCode = ErrorCode.E422,
    message: String = code.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
