/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   18 行  函数 decode
 * 第   22 行  函数 errorCode
 * 第   26 行  函数 errorResponse
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
