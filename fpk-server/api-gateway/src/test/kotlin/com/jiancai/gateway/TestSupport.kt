/*
 * @file fpk-server/api-gateway/src/test/kotlin/com/jiancai/gateway/TestSupport.kt
 * @role 断言辅助：把响应体按契约反序列化（线格式一定来自 shared）。
 * @exports （无）
 * @depends shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.gateway

import com.jiancai.shared.ContractJson
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.errors.ErrorResponse
import com.jiancai.shared.errors.toErrorCode
import kotlinx.serialization.KSerializer

/** 断言辅助：把响应体按契约反序列化（线格式一定来自 shared）。 */
internal fun <T> ApiResponse.decode(serializer: KSerializer<T>): T =
    ContractJson.json.decodeFromString(serializer, body)

/** 错误响应的共享错误码（未知码返回 null）。 */
internal fun ApiResponse.errorCode(): ErrorCode? =
    ContractJson.json.decodeFromString(ErrorResponse.serializer(), body).toErrorCode()

/** 错误响应的原始错误体（用于断言 traceId / details）。 */
internal fun ApiResponse.errorResponse(): ErrorResponse =
    ContractJson.json.decodeFromString(ErrorResponse.serializer(), body)
