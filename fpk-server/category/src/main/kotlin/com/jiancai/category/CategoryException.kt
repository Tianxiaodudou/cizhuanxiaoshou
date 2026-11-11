/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   25 行  类 CategoryException
 */

package com.jiancai.category

import com.jiancai.shared.errors.ErrorCode

/**
 * 型号库用例失败（S05）。
 *
 * 错误码只取 `shared` 的 [ErrorCode]（唯一真源），网关（S13）按 [code] 映射 HTTP 状态与统一响应体，
 * 本模块不另写映射表。参数顺序与其它模块一致：`code` 在前，`message` 默认取 `code.message`。
 *
 * 本模块用到的码：
 * - `E422` 入参校验失败（空名称 / 空型号 / 批号类型非法 / 装箱规格非法 / id 口径错误）；
 * - `E404` 型号不存在（get / update / delete）；
 * - `E409` 「品类 + 型号」重复（唯一约束裁决，含并发兜底）。
 *
 * `E403` 不会从本模块抛出：非管理员请求在 S13 就被拦下，根本不进入本模块（铁律 7）。
 *
 * @property code 共享契约中的错误码
 */
class CategoryException(
    val code: ErrorCode = ErrorCode.E422,
    message: String = code.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
