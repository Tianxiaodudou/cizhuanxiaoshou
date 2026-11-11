/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   30 行  类 SettleException
 */

package com.jiancai.settlement

import com.jiancai.shared.errors.ErrorCode

/**
 * 结算用例失败（S11）。
 *
 * 错误码只取 `shared` 的 [ErrorCode]（唯一真源），S13 api-gateway 按 [code] 映射 HTTP 状态与
 * 统一响应体，本模块不另写映射表（铁律 9）。
 *
 * 本模块用到的码：
 * - `E5001` 结算单不存在（audit）；
 * - `E5002` 结算金额超过待结算余额（收款超过未收 / 退款超过应退）；
 * - `E5003` 结算单已审核，不可重复审核（含并发条件更新失败）；
 * - `E5004` 同一幂等键被用于另一笔结算（载荷冲突）；
 * - `E2001` 订单不存在（保留 S08 错误码）；
 * - `E6005` 操作者账号不存在（保留 S03 错误码）；
 * - `E422` 入参格式不合法（id、单号、金额、长度、控制字符等）；
 * - `E9001` 取号冲突（保留 S07 错误码，可重试）。
 *
 * `E403` 不会从本模块抛出：权限只在 S13 判断（铁律 7）。
 *
 * @property code 共享契约中的错误码
 */
class SettleException(
    val code: ErrorCode = ErrorCode.E422,
    message: String = code.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
