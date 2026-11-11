/*
 * @file fpk-server/note/src/main/kotlin/com/jiancai/note/NoteException.kt
 * @role 备注单用例失败（S10）。
 * @exports NoteException
 * @depends shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ErrorMapping.kt, fpk-server/note/src/main/kotlin/com/jiancai/note/NoteService.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.note

import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.VoidNoteCmd
import com.jiancai.shared.errors.ErrorCode

/**
 * 备注单用例失败（S10）。
 *
 * 错误码只取 `shared` 的 [ErrorCode]（唯一真源），S13 api-gateway 按 [code] 映射 HTTP 状态与
 * 统一响应体，本模块不另写映射表（铁律 9）。
 *
 * 本模块用到的码：
 * - `E4001` 备注单不存在（作废）；
 * - `E4002` 备注单已作废（不可重复作废）；
 * - `E4003` 原订单不存在，无法创建备注单（由 S08 的 `E2001` 归一化而来）；
 * - `E422` 入参格式不合法（单号、备注、原因、操作者 id 等）；
 * - `E9001` 来自 S07 的取号冲突，原样保留给网关（可重试）。
 *
 * `E403` 不会从本模块抛出：权限只在 S13 判断（铁律 7）。
 *
 * @property code 共享契约中的错误码
 */
class NoteException(
    val code: ErrorCode = ErrorCode.E422,
    message: String = code.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
