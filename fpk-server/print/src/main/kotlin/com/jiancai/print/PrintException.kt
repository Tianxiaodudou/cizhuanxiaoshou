/*
 * @file fpk-server/print/src/main/kotlin/com/jiancai/print/PrintException.kt
 * @role 打印用例失败（S12）。
 * @exports PrintException
 * @depends shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ErrorMapping.kt, fpk-server/print/src/main/kotlin/com/jiancai/print/PrintService.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.print

import com.jiancai.shared.errors.ErrorCode

/**
 * 打印用例失败（S12）。
 *
 * 错误码只取 `shared` 的 [ErrorCode]（唯一真源），S13 api-gateway 按 [code] 映射 HTTP 状态与
 * 统一响应体，本模块不另写映射表（铁律 9）。
 *
 * 本模块用到的码：
 * - `E7001` 打印数据不存在（订单号 / 备注单号都查不到）；
 * - `E7002` 打印次数已达上限（同一单据重复打印超过部署上限）；
 * - `E422` 入参格式不合法（单号为空 / 超长 / 控制字符，打印人 id <= 0）。
 *
 * `E403` 不会从本模块抛出：权限只在 S13 判断（铁律 7）。
 *
 * @property code 共享契约中的错误码
 */
class PrintException(
    val code: ErrorCode = ErrorCode.E422,
    message: String = code.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
