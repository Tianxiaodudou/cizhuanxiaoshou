/*
 * @file fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayException.kt
 * @role 网关自身产生的失败（路由 / 鉴权 / 幂等 / 编解码）。
 * @exports GatewayException
 * @depends shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ApiGateway.kt, fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/AuthGate.kt, fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ErrorMapping.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.gateway

import com.jiancai.shared.errors.ErrorCode

/**
 * 网关自身产生的失败（路由 / 鉴权 / 幂等 / 编解码）。
 *
 * 业务模块的异常不包装成它：网关按 `ErrorCode` 统一映射（见 [ErrorMapping]），
 * 因此线上错误码只有一个来源（`shared` 的 `ErrorCode`，铁律 9）。
 *
 * @property code 共享契约中的错误码（决定 HTTP 状态与客户端动作）
 * @property details 字段级明细（可选，随 `ErrorResponse.details` 下发）
 */
class GatewayException(
    val code: ErrorCode,
    message: String = code.message,
    val details: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)
