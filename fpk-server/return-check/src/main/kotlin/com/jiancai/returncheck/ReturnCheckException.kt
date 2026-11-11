/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   29 行  类 ReturnCheckException
 */

package com.jiancai.returncheck

import com.jiancai.shared.errors.ErrorCode

/**
 * 退单校验失败（S09）。
 *
 * 错误码只取 `shared` 的 [ErrorCode]（唯一真源），S13 api-gateway 按 [code] 映射 HTTP 状态与
 * 统一响应体，本模块不另写映射表（铁律 9）。
 *
 * 本模块用到的码（1.1.0 起新增 E3004 / E3005 / E413）：
 * - `E3001` 原订单不存在（调用方传了不存在 / 已删除的订单）；
 * - `E3002` 该批次不可退（单据类型不可退、批次在原单上但已退满）；
 * - `E3003` 可退数量不足（含同批次多行累加后超额）；
 * - `E3004` 原单已作废，不可退货；
 * - `E3005` 退货批次与原单不符（批次不在原单明细上）；
 * - `E413` 数据量超过允许上限（明细行数 / 退货单翻页超过策略上限）；
 * - `E422` 入参格式不合法（订单号、型号 id、数量格式 / 小数位）。
 *
 * `E403` 不会从本模块抛出：权限只在 S13 判断（铁律 7）。
 *
 * @property code 共享契约中的错误码
 */
class ReturnCheckException(
    val code: ErrorCode = ErrorCode.E422,
    message: String = code.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
