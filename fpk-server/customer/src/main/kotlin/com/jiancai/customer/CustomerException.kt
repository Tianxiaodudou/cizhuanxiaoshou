package com.jiancai.customer

import com.jiancai.shared.errors.ErrorCode

/**
 * 客户用例失败（S04）。
 *
 * 错误码只取 `shared` 的 [ErrorCode]（唯一真源），S13 api-gateway 按 [code] 映射 HTTP 状态与
 * 统一响应体，本模块不另写映射表（铁律 9）。
 *
 * 共享错误码里没有客户专属段位（`shared/errors/ErrorCode.kt` 只到 E1xxx–E7xxx、E9xxx），
 * 本模块因此复用通用码：`E422` 入参不合法、`E404` 客户不存在、`E409` 电话已建档。
 * 需要客户专属码时必须先在 `shared` 追加（契约只增不改），不能在本模块自造字符串。
 *
 * @property code 共享错误码
 */
class CustomerException(
    val code: ErrorCode = ErrorCode.E422,
    message: String = code.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
