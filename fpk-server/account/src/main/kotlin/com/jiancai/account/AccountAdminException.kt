/*
 * @file fpk-server/account/src/main/kotlin/com/jiancai/account/AccountAdminException.kt
 * @role 账号管理用例失败（S03）。
 * @exports AccountAdminException
 * @depends shared
 * @calledBy fpk-server/account/src/main/kotlin/com/jiancai/account/AccountAdminService.kt, fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ErrorMapping.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/AccountStoreAdapter.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.account

import com.jiancai.shared.errors.ErrorCode

/**
 * 账号管理用例失败（S03）。
 *
 * 错误码只取 `shared` 的 [ErrorCode]（唯一真源），网关（S13）按 [code] 映射 HTTP 状态与
 * 统一响应体，本模块不另写映射表。
 *
 * 参数顺序刻意把 [code] 放第一位，[message] 默认取 `code.message`：
 * 默认文案只在 `shared` 里维护，本模块只在需要更具体提示时覆盖（如「用户名不能为空」）。
 *
 * @property code 共享错误码：`E6001` 账号已存在（由 S02 的唯一约束产生）、`E6003` 不能删除最后一个管理员、
 *   `E6005` 账号不存在、`E422` 入参校验失败
 */
class AccountAdminException(
    val code: ErrorCode = ErrorCode.E422,
    message: String = code.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
