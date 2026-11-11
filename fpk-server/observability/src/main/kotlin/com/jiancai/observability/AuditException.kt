/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   23 行  类 AuditException
 */

package com.jiancai.observability

import com.jiancai.shared.errors.ErrorCode

/**
 * 审计用例失败（S14）。
 *
 * 错误码只取 `shared` 的 [ErrorCode]（唯一真源），S13 按 [code] 映射 HTTP 状态与统一响应体。
 * 本模块只用通用码（不新增业务段位）：
 * - `E422` 入参非法（动作 / 主键 / 操作者 / 摘要 / 明细 / 查询条件）；
 * - `E500` 审计数据损坏（`details` 无法还原）；
 * - `E503` 数据库不可用。
 *
 * `E403` 不会从本模块抛出：权限只在 S13 判断（铁律 7）。
 *
 * @property code 共享契约中的错误码
 */
class AuditException(
    val code: ErrorCode = ErrorCode.E422,
    message: String = code.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
