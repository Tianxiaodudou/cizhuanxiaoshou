/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   43 行  接口 AccountStore
 * 第   46 行  函数 list
 * 第   49 行  函数 get
 * 第   52 行  函数 create
 * 第   55 行  函数 update
 * 第   58 行  函数 delete
 */

package com.jiancai.account

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.UpdateAccountCmd

/**
 * S03 需要的**账号存储能力**（由 S02 提供，本模块只声明、不实现）。
 *
 * 为什么不是 S03 自己读写 `auth_account`：铁律 3 / 10 + `auth/module.md` 的约定 ——
 * 账号表归 S02 独占（`AuthRepository` 是唯一 SQL 出口），密码只能经 S02 哈希，
 * 其它模块要账号数据只能调 S02 的 Port。S03 因此只描述「账号管理用例需要什么」，
 * 真实数据由 S02 提供，本模块里没有一句账号表 SQL、也没有密码哈希。
 *
 * 落地方式（组合根，不属于任何业务模块）：
 * ```
 * // 等 S02 按 auth/module.md 的规划新增 AccountStorePort 后：
 * class AccountStoreAdapter(private val auth: AccountStorePort) : AccountStore { ... } // ≈20 行，只做转调 + 错误码翻译
 * val accountAdmin: AccountAdminPort = AccountAdminService(db.txRunner, AccountStoreAdapter(authStore))
 * ```
 * 适配器把 S02 的错误（`E6001` 账号已存在 / `E6005` 账号不存在 / `E422`）翻译成
 * [AccountAdminException]，因此本模块**不感知** S02 的异常类型、也不编译期依赖 S02。
 *
 * 实现约定（适配器必须满足）：
 * - 全部方法在调用方事务内执行，不得自开事务；
 * - 用户名唯一性由实现侧（S02 的唯一索引）保证，重名抛 [AccountAdminException] `E6001`；
 * - 入参里的明文密码只在内存里停留，由实现侧哈希后落库，**永不回传、永不记录**；
 * - [create] / [update] 的返回是 [AccountDto]（没有密码字段），不得返回持久化实体；
 * - [update] 的 `password` 为 null 时保留原密码。
 */
interface AccountStore {

    /** 全部账号（实现侧应保证 id 升序；[AccountAdminService] 会再排序一次兜底）。 */
    fun list(tx: TxContext): List<AccountDto>

    /** 按主键读取；不存在返回 null（由 Service 统一转 `E6005`）。 */
    fun get(tx: TxContext, id: Long): AccountDto?

    /** 新增账号并返回落库后的 DTO；用户名重复抛 `E6001`。 */
    fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto

    /** 修改账号并返回落库后的 DTO；账号不存在返回 null。 */
    fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto?

    /** 删除账号；账号不存在返回 false。 */
    fun delete(tx: TxContext, id: Long): Boolean
}
