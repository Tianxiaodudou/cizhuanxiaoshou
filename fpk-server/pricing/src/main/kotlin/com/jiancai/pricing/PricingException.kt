/*
 * @file fpk-server/pricing/src/main/kotlin/com/jiancai/pricing/PricingException.kt
 * @role 定价用例失败（S06）。
 * @exports PricingException
 * @depends shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ErrorMapping.kt, fpk-server/order/src/main/kotlin/com/jiancai/order/OrderService.kt, fpk-server/pricing/src/main/kotlin/com/jiancai/pricing/PriceService.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.pricing

import com.jiancai.shared.errors.ErrorCode

/**
 * 定价用例失败（S06）。
 *
 * 错误码只取 `shared` 的 [ErrorCode]（唯一真源），S13 api-gateway 按 [code] 映射 HTTP 状态与
 * 统一响应体，本模块不另写映射表（铁律 9）。
 *
 * 本模块用到的码：
 * - `E1001` 价格已跨日锁定，不可修改；
 * - `E1002` 未配置价格（resolve 未命中）；
 * - `E1003` 改价原因必填；
 * - `E422` 入参校验失败（id、金额、批号、原因长度等）；
 * - `E404` 客户 / 型号不存在（来自 S04 / S05 Port 的错误码，本模块原样保留）。
 *
 * `E403` 不会从本模块抛出：非管理员请求在 S13 就被拦下，根本不进入本模块（铁律 7）。
 *
 * @property code 共享契约中的错误码
 */
class PricingException(
    val code: ErrorCode = ErrorCode.E422,
    message: String = code.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
