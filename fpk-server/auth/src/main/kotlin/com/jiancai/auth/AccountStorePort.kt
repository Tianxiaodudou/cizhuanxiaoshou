/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   37 行  接口 AccountStorePort
 * 第   40 行  函数 list
 * 第   43 行  函数 get
 * 第   50 行  函数 create
 * 第   58 行  函数 update
 * 第   65 行  函数 delete
 */

package com.jiancai.auth

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.UpdateAccountCmd

/**
 * 账号存储 Port：S02 对外的**第二个** Port，供 S03 account（管理员用例层）取账号数据。
 *
 * 为什么由 S02 提供：`auth_account` 归 S02 独占（[AuthRepository] 是唯一 SQL 出口），
 * 密码只能经 S02 的 [PasswordHasher] 哈希；S03 不读写本表、不接触哈希，只声明它需要什么
 * （见 `fpk-server/account/.../AccountStore.kt`）。该接口只增不改，`AuthPort` 不受影响。
 *
 * 契约口径（与 S03 `AccountStore` 一一对应）：
 * - 所有方法都在调用方事务内执行，**不自开事务**（铁律 4）；S03 的适配器传同一个 [TxContext]；
 * - 出参一律 [AccountDto]，不暴露 `auth_account` 的持久化结构（铁律 2）；
 * - 明文密码只在内存中停留：`create` / `update` 内部哈希后落库，永不回传、不记录；
 * - 用户名规范化（trim + 小写）与唯一性由本模块裁决，重名抛 `E6001`；
 * - `get` / `update` / `delete` 对不存在的账号返回 null / false，由调用方决定 `E6005`；
 * - 入参不合法（空用户名 / 空显示名 / 密码短于 [AuthService.MIN_PASSWORD_LENGTH]）抛 `E422`。
 *
 * 权限不在这里判断（铁律 7）：本 Port 的参数里没有操作者身份 / 角色，
 * 「只有管理员能调」由 S13 api-gateway 在进入 S03 之前拦截。
 */
interface AccountStorePort {

    /** 全部账号，按 id 升序（S03 还会再排一次序兜底）。只读。 */
    fun list(tx: TxContext): List<AccountDto>

    /** 按主键读取；不存在返回 null。 */
    fun get(tx: TxContext, id: Long): AccountDto?

    /**
     * 新增账号（默认启用），返回落库后的 DTO。
     *
     * @throws AuthException `E422` 入参不合法、`E6001` 用户名已存在（大小写不敏感）
     */
    fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto

    /**
     * 修改显示名 / 角色 / 启停；`cmd.password` 为 null 或空白时保留原密码。
     *
     * @return 更新后的 DTO；账号不存在返回 null
     * @throws AuthException `E422` 显示名为空或新密码过短
     */
    fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto?

    /**
     * 删除账号。
     *
     * @return true 已删除；false 账号不存在
     */
    fun delete(tx: TxContext, id: Long): Boolean
}
