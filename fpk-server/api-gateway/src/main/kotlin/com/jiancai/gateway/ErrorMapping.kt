/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   48 行  对象 ErrorMapping
 * 第   51 行  函数 codeOf
 * 第   76 行  函数 toResponse
 * 第   87 行  函数 encode
 * 第   95 行  函数 responseOf
 * 第  106 行  函数 messageOf
 * 第  116 行  类 MappedError
 */

package com.jiancai.gateway

import com.jiancai.account.AccountAdminException
import com.jiancai.auth.AuthException
import com.jiancai.category.CategoryException
import com.jiancai.customer.CustomerException
import com.jiancai.db.DbException
import com.jiancai.note.NoteException
import com.jiancai.observability.AuditException
import com.jiancai.order.OrderException
import com.jiancai.pricing.PricingException
import com.jiancai.print.PrintException
import com.jiancai.returncheck.ReturnCheckException
import com.jiancai.serial.SerialException
import com.jiancai.settlement.SettleException
import com.jiancai.shared.ContractJson
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.errors.ErrorResponse
import com.jiancai.shared.errors.toResponse
import kotlinx.serialization.SerializationException

/**
 * 错误码统一映射（验收「错误码统一」）。
 *
 * 唯一口径：**HTTP 状态、分类、默认文案全部取自 `shared` 的 [ErrorCode]**，
 * 网关不维护「错误 → 状态码」的第二张表（铁律 9）。
 *
 * 映射规则：
 * - 业务模块异常：读它自带的 `ErrorCode`（各模块 `*Exception.code`），网关不认识任何业务语义；
 * - 网关异常：直接用自己的 `code`；
 * - 入参格式（`IllegalArgumentException` / 序列化失败）：`E422` / `E400`；
 * - 库层故障：`E503`（可重试）；其余未知异常：`E500`（**不透出内部消息**）。
 *
 * 安全口径：`E500` / `E503` 只返回 `ErrorCode.message` 的通用文案，绝不把栈或 SQL 文本下发。
 */
object ErrorMapping {

    /** 从任意异常中取共享错误码；未知异常 = `E500`。 */
    fun codeOf(failure: Throwable): ErrorCode = when (failure) {
        is GatewayException -> failure.code
        is AuthException -> failure.code
        is AccountAdminException -> failure.code
        is CustomerException -> failure.code
        is CategoryException -> failure.code
        is PricingException -> failure.code
        is SerialException -> failure.code
        is OrderException -> failure.code
        is ReturnCheckException -> failure.code
        is NoteException -> failure.code
        is SettleException -> failure.code
        is PrintException -> failure.code
        is AuditException -> failure.code
        is DbException, is java.sql.SQLException -> ErrorCode.E503
        is SerializationException -> ErrorCode.E400
        is IllegalArgumentException -> ErrorCode.E422
        else -> ErrorCode.E500
    }

    /**
     * 异常 → 统一错误响应。
     *
     * @param traceId 本次请求追踪 id（同时写进响应头，便于对日志）
     */
    fun toResponse(failure: Throwable, traceId: String): MappedError {
        val code = codeOf(failure)
        val response = code.toResponse(
            message = messageOf(failure, code),
            details = (failure as? GatewayException)?.details ?: emptyMap(),
            traceId = traceId,
        )
        return MappedError(code, response)
    }

    /** 统一的错误响应体编码（`ErrorResponse` 来自 shared，线格式唯一）。 */
    fun encode(response: ErrorResponse): String =
        ContractJson.json.encodeToString(ErrorResponse.serializer(), response)

    /**
     * 直接构造一个统一错误响应（供适配层在「还没进网关」时使用，例如不支持的方法）。
     *
     * 仍走同一张映射表，保证线上错误体只有一个来源。
     */
    fun responseOf(
        code: ErrorCode,
        message: String? = null,
        details: Map<String, String> = emptyMap(),
        traceId: String? = null,
    ): ApiResponse = ApiResponse(
        status = code.httpStatus,
        body = encode(code.toResponse(message, details, traceId)),
    )

    /** 可下发的用户文案：5xx 一律用通用文案，业务码优先用异常自带的消息。 */
    private fun messageOf(failure: Throwable, code: ErrorCode): String = when {
        code.httpStatus >= 500 -> code.message
        failure.message.isNullOrBlank() -> code.message
        else -> failure.message!!
    }
}

/**
 * 映射结果：共享错误码 + 可直接下发的响应体。
 */
data class MappedError(val code: ErrorCode, val response: ErrorResponse)
