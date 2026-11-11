/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   19 行  类 AuthException
 */

package com.jiancai.auth

import com.jiancai.shared.errors.ErrorCode

/**
 * 登录 / 校验失败（S02）。
 *
 * 网关（S13）按 [code] 映射 HTTP 状态与统一错误响应体，
 * 禁止各模块另行维护「错误 → 状态码」映射表（唯一真源是 `shared` 的 `ErrorCode`）。
 *
 * @property code 共享契约中的错误码：`E6002` 账号或密码错误、`E6004` 账号已禁用、
 *   `E401` token 无效或过期、`E6001` 账号已存在、`E422` 入参校验失败
 */
class AuthException(
    message: String,
    val code: ErrorCode = ErrorCode.E401,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
