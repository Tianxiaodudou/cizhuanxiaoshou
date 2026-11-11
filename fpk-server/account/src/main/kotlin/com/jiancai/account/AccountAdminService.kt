/*
 * @file fpk-server/account/src/main/kotlin/com/jiancai/account/AccountAdminService.kt
 * @role [AccountAdminPort] 的唯一实现：管理员账号 CRUD 的用例层。
 * @exports AccountAdminService
 * @depends db-core, shared
 * @calledBy fpk-server/app/src/main/kotlin/com/jiancai/app/App.kt
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.account

import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.UpdateAccountCmd
import com.jiancai.shared.enums.Role
import com.jiancai.shared.errors.ErrorCode

/**
 * [AccountAdminPort] 的唯一实现：管理员账号 CRUD 的用例层。
 *
 * 职责边界：
 * - **不判权限**：本类拿不到调用者身份（构造参数里没有 token / 操作者 / 角色），权限判断只在 S13；
 * - **不碰密码**：明文密码只透传给 [AccountStore]（由 S02 哈希落库），本模块不哈希、不落库、不回传；
 * - **不写 SQL**：账号表归 S02，本类只调 [AccountStore]（铁律 3 / 10）；
 * - 自己只负责「用例级」的事：入参裁剪与空值校验、账号存在性、以及
 *   「系统至少保留一个已启用管理员」这条业务不变量（否则会把管理入口改没）。
 *
 * @param txRunner db-core 的事务 Port（铁律 4：全部写操作经它包裹）
 * @param store S02 的账号存储能力（见 [AccountStore] 与 module.md「与 S02 的分工」）
 */
class AccountAdminService(
    private val txRunner: TxRunner,
    private val store: AccountStore,
) : AccountAdminPort {

    // —— 读 ——

    override fun list(): List<AccountDto> = txRunner.run { tx -> list(tx) }

    override fun list(tx: TxContext): List<AccountDto> = store.list(tx).sortedBy { it.id }

    override fun get(id: Long): AccountDto = txRunner.run { tx -> get(tx, id) }

    override fun get(tx: TxContext, id: Long): AccountDto =
        store.get(tx, id) ?: throw AccountAdminException(ErrorCode.E6005)

    // —— 写 ——

    override fun create(cmd: CreateAccountCmd): AccountDto = txRunner.run { tx -> create(tx, cmd) }

    override fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto {
        val username = cmd.username.trim()
        val displayName = cmd.displayName.trim()
        if (username.isEmpty()) throw AccountAdminException(ErrorCode.E422, "用户名不能为空")
        if (displayName.isEmpty()) throw AccountAdminException(ErrorCode.E422, "显示名称不能为空")
        // 密码长度 / 强度 / 哈希全部属于 S02 的密码策略：本模块不重复实现，也不接触哈希。
        // 用户名是否重复同样由 S02 的唯一约束（username_key）裁决，重名以 E6001 上抛。
        return store.create(tx, cmd.copy(username = username, displayName = displayName))
    }

    override fun update(cmd: UpdateAccountCmd): AccountDto = txRunner.run { tx -> update(tx, cmd) }

    override fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto {
        val displayName = cmd.displayName.trim()
        if (displayName.isEmpty()) throw AccountAdminException(ErrorCode.E422, "显示名称不能为空")

        val current = store.get(tx, cmd.id) ?: throw AccountAdminException(ErrorCode.E6005)
        val target = cmd.copy(
            displayName = displayName,
            // 契约口径「password 为空表示不改密码」：null 与空白都按「不改」处理，绝不设空密码。
            password = cmd.password?.takeIf { it.isNotBlank() },
        )

        val wasEnabledAdmin = current.isEnabledAdmin()
        val staysEnabledAdmin = target.role == Role.ADMIN && target.enabled
        if (wasEnabledAdmin && !staysEnabledAdmin && isLastEnabledAdmin(tx, current)) {
            throw AccountAdminException(ErrorCode.E6003)
        }

        return store.update(tx, target) ?: throw AccountAdminException(ErrorCode.E6005)
    }

    override fun delete(id: Long) {
        txRunner.run { tx -> delete(tx, id) }
    }

    override fun delete(tx: TxContext, id: Long) {
        val current = store.get(tx, id) ?: throw AccountAdminException(ErrorCode.E6005)
        if (current.isEnabledAdmin() && isLastEnabledAdmin(tx, current)) {
            throw AccountAdminException(ErrorCode.E6003)
        }
        // 并发下可能已被别人删掉：回查结果为准，不静默成功。
        if (!store.delete(tx, id)) throw AccountAdminException(ErrorCode.E6005)
    }

    // —— 业务不变量 ——

    /**
     * [current] 是否为「系统里最后一个可用的管理员」。
     *
     * 只看**已启用**的管理员：已停用的管理员本来就不能登录，删掉或降级它不会收窄管理入口。
     */
    private fun isLastEnabledAdmin(tx: TxContext, current: AccountDto): Boolean =
        current.isEnabledAdmin() &&
            store.list(tx).none { it.id != current.id && it.isEnabledAdmin() }

    private fun AccountDto.isEnabledAdmin(): Boolean = enabled && role == Role.ADMIN
}
