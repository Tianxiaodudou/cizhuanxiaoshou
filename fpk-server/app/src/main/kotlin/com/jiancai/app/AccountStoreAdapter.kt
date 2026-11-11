/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   39 行  类 AccountStoreAdapter
 * 第   43 行  函数 list
 * 第   45 行  函数 get
 * 第   47 行  函数 create
 * 第   49 行  函数 update
 * 第   51 行  函数 delete
 * 第   58 行  函数 translate
 */

package com.jiancai.app

import com.jiancai.account.AccountAdminException
import com.jiancai.account.AccountStore
import com.jiancai.auth.AccountStorePort
import com.jiancai.auth.AuthException
import com.jiancai.db.TxContext
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.UpdateAccountCmd

/**
 * S03 `AccountStore` ↔ S02 `AccountStorePort` 的薄适配器（约 20 行，只做转调 + 错误翻译）。
 *
 * 为什么必须存在：S03 只声明它需要的账号存储能力（`account/AccountStore.kt`），编译期不依赖
 * `:fpk-server:auth`（依赖倒置，避免环）；S02 提供结构相同的 `AccountStorePort`。
 * 两个接口靠本适配器在编译期钉死 —— 任何一边改签名，这里立刻编译失败。
 *
 * 职责边界：
 * - **只转调**：不排序（S03 会再排一次）、不校验、不判权限；
 * - **只翻译异常**：把 S02 的 [AuthException]（`E6001` 重名 / `E422` 入参）翻成
 *   [AccountAdminException]，让 S03 / S13 看不到 S02 的异常类型；
 * - **不自开事务**：每个方法都透传调用方的 [TxContext]（铁律 4）。
 *
 * 组合根（[App]）是唯一构造它的地方：`AccountStoreAdapter(authService)`。
 */
class AccountStoreAdapter(
    private val auth: AccountStorePort,
) : AccountStore {

    override fun list(tx: TxContext): List<AccountDto> = translate { auth.list(tx) }

    override fun get(tx: TxContext, id: Long): AccountDto? = translate { auth.get(tx, id) }

    override fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto = translate { auth.create(tx, cmd) }

    override fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto? = translate { auth.update(tx, cmd) }

    override fun delete(tx: TxContext, id: Long): Boolean = translate { auth.delete(tx, id) }

    /**
     * S02 的失败一律以 [AuthException] 抛到模块外；S03 只认 [AccountAdminException]。
     * 错误码是 `shared` 的同一份 [com.jiancai.shared.errors.ErrorCode]，这里只换异常类型与文案来源，
     * 不新建映射表。
     */
    private fun <T> translate(call: () -> T): T =
        try {
            call()
        } catch (failure: AuthException) {
            throw AccountAdminException(
                code = failure.code,
                message = failure.message ?: failure.code.message,
                cause = failure,
            )
        }
}
