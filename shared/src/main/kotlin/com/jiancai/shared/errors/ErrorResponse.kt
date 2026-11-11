/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   21 行  类 ErrorResponse
 * 第   32 行  函数 toResponse
 * 第   44 行  函数 toErrorCode
 */

package com.jiancai.shared.errors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 统一错误响应体（唯一真源）：S13 产出，C00 统一解析。
 *
 * `code` 用字符串而非枚举，保证服务端新增错误码时旧客户端不会反序列化失败；
 * 客户端通过 [toErrorCode] 映射到已知枚举，未知码走通用兜底文案。
 */
@Serializable
data class ErrorResponse(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String,
    @SerialName("details") val details: Map<String, String> = emptyMap(),
    @SerialName("trace_id") val traceId: String? = null,
)

/**
 * 服务端统一映射：错误码 → 响应体。
 * 调用方可覆盖为用户更具体的文案、附带字段级明细与服务端 traceId。
 */
fun ErrorCode.toResponse(
    message: String? = null,
    details: Map<String, String> = emptyMap(),
    traceId: String? = null,
): ErrorResponse = ErrorResponse(
    code = name,
    message = message ?: this.message,
    details = details,
    traceId = traceId,
)

/** 客户端统一解析：未知错误码返回 null，由 C00 兜底展示（前向兼容）。 */
fun ErrorResponse.toErrorCode(): ErrorCode? = ErrorCode.fromWire(code)
