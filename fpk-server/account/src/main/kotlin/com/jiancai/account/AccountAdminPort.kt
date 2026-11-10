package com.jiancai.account

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.UpdateAccountCmd

/**
 * 账号管理 Port：S03 唯一对外入口（S13 api-gateway 转发管理员请求，C11 feature-admin 消费）。
 *
 * 硬边界（铁律 7 + 本模块约束「禁止模块内自判权限」）：
 * - 本 Port **不接收**任何「调用者身份」参数（没有 token、没有操作者 [AccountDto]、没有 [com.jiancai.shared.enums.Role]）；
 *   所以这里既无法判断、也不允许判断「当前用户是不是管理员」；
 * - 管理员身份与 `ADMIN` 角色的拦截**只在 S13** 完成（`AuthPort.verify` → `role == ADMIN`，否则 `E403`），
 *   本模块被调用时已认定调用者有权执行；把权限判断下沉到客户端或业务模块都是违规；
 * - 因此「非管理员被 api-gateway 拒绝」这条验收在 S13 的网关用例里验证，本模块只保证不自判权限（见 module.md）。
 *
 * 事务口径（铁律 4）：所有写操作都提供带 [TxContext] 的重载，供调用方把「账号变更 + 其它写入」
 * 放进同一个 db-core 事务；不带 tx 的便捷重载内部仍走 [com.jiancai.db.TxRunner.run]，不存在非事务写入。
 *
 * 返回口径（铁律 2）：全部返回 [AccountDto]（不含密码字段）或标量，绝不返回持久化实体。
 */
interface AccountAdminPort {

    /**
     * 在调用方事务内列出全部账号（按 id 升序，供管理员列表页稳定展示）。
     *
     * @param tx 由 db-core 的 `TxRunner.run {}` 产生的事务上下文
     */
    fun list(tx: TxContext): List<AccountDto>

    /** 便捷列表：内部自开事务。 */
    fun list(): List<AccountDto>

    /**
     * 在调用方事务内读取单个账号。
     *
     * @throws AccountAdminException 账号不存在（`E6005`，404）
     */
    fun get(tx: TxContext, id: Long): AccountDto

    /** 便捷读取：内部自开事务。 */
    fun get(id: Long): AccountDto

    /**
     * 在调用方事务内新增账号。
     *
     * 用户名与显示名首尾空白会被裁掉；用户名唯一性由 S02 的账号表唯一约束兜底，
     * 密码规则（长度 / 哈希）只由 S02 决定 —— 本模块不碰密码哈希，也不落明文。
     *
     * @throws AccountAdminException 用户名为空 / 显示名为空（`E422`，422）、同名账号已存在（`E6001`，409）
     */
    fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto

    /** 便捷新增：内部自开事务。 */
    fun create(cmd: CreateAccountCmd): AccountDto

    /**
     * 在调用方事务内修改账号（显示名、角色、启用状态；[UpdateAccountCmd.password] 为空表示不改密码）。
     *
     * 业务不变量：「系统里必须至少剩一个已启用的管理员」。若本次修改会让最后一个可用管理员
     * 被降级或停用，直接拒绝（`E6003`），避免把管理入口改没。
     *
     * @throws AccountAdminException 显示名为空（`E422`）、账号不存在（`E6005`）、
     *   会移除最后一个可用管理员（`E6003`，409）
     */
    fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto

    /** 便捷修改：内部自开事务。 */
    fun update(cmd: UpdateAccountCmd): AccountDto

    /**
     * 在调用方事务内删除账号（删除后该账号的旧 token 会被 S02 的校验立即判为失效）。
     *
     * 同样的「至少剩一个已启用管理员」不变量：删除最后一个可用管理员被拒（`E6003`）。
     *
     * @throws AccountAdminException 账号不存在（`E6005`）、删的是最后一个可用管理员（`E6003`）
     */
    fun delete(tx: TxContext, id: Long)

    /** 便捷删除：内部自开事务。 */
    fun delete(id: Long)
}
