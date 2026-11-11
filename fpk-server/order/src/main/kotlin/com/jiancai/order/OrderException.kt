/*
 * @file fpk-server/order/src/main/kotlin/com/jiancai/order/OrderException.kt
 * @role 订单用例失败（S08）。
 * @exports OrderException
 * @depends shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ErrorMapping.kt, fpk-server/note/src/main/kotlin/com/jiancai/note/NoteService.kt, fpk-server/order/src/main/kotlin/com/jiancai/order/OrderPriceRecalculator.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.order

import com.jiancai.shared.errors.ErrorCode

/**
 * 订单用例失败（S08）。
 *
 * 错误码只取 `shared` 的 [ErrorCode]（唯一真源），S13 api-gateway 按 [code] 映射 HTTP 状态与
 * 统一响应体，本模块不另写映射表（铁律 9）。
 *
 * 本模块用到的码：
 * - `E2001` 订单不存在（update / getReceivable）；
 * - `E2002` 乐观锁版本冲突，客户端刷新后重试；
 * - `E2003` 非当日订单不可修改 / 订单已作废；
 * - `E2004` 订单已结清，不可修改；
 * - `E2005` 订单明细不能为空；
 * - `E2006` 数量必须大于 0；
 * - `E2007` 批号与型号不匹配；
 * - `E422` 入参格式不合法（id、日期、页码、长度、控制字符等）；
 * - `E404` / `E1002` / `E9001` 等来自 S04 / S05 / S06 / S07 的错误码，原样保留给网关。
 *
 * `E403` 不会从本模块抛出：权限只在 S13 判断（铁律 7）。
 *
 * @property code 共享契约中的错误码
 */
class OrderException(
    val code: ErrorCode = ErrorCode.E422,
    message: String = code.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
