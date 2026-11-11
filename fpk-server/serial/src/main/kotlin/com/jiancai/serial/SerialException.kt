/*
 * @file fpk-server/serial/src/main/kotlin/com/jiancai/serial/SerialException.kt
 * @role 取号失败（S07）。
 * @exports SerialException
 * @depends shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ErrorMapping.kt, fpk-server/note/src/main/kotlin/com/jiancai/note/NoteService.kt, fpk-server/order/src/main/kotlin/com/jiancai/order/OrderService.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.serial

import com.jiancai.shared.errors.ErrorCode

/**
 * 取号失败（S07）。
 *
 * 网关（S13）按 [code] 映射错误响应；默认 [ErrorCode.E9001]
 * （HTTP 409 + retryable，客户端可安全重试）。
 *
 * @property code 共享契约中的错误码，禁止各模块另写映射
 */
class SerialException(
    message: String,
    val code: ErrorCode = ErrorCode.E9001,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
