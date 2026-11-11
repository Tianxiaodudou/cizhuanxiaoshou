/*
 * @file fpk-server/auth/src/main/kotlin/com/jiancai/auth/AuthPort.kt
 * @role 登录 / token 校验 Port：S13 api-gateway 做鉴权、C01 只消费 token（S02 另有 [AccountStorePort] 给 S03）。
 * @exports AuthPort
 * @depends db-core, shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/AuthGate.kt, fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayDeps.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/App.kt
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.auth

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.LoginCmd
import com.jiancai.shared.dto.LoginResultDto

/**
 * 登录 / token 校验 Port：S13 api-gateway 做鉴权、C01 只消费 token（S02 另有 [AccountStorePort] 给 S03）。
 *
 * 安全口径：
 * - 密码只以 PBKDF2 哈希落库，任何 Port 方法都不返回、不打印密码（[AccountDto] 无密码字段）；
 * - token 由服务端签发并签名，客户端只保存不解释（禁止客户端解析 / 自签 token 充当鉴权，
 *   鉴权判断点唯一在服务端，铁律「禁止鉴权下沉到客户端」）；
 * - 用户名不存在与密码错误返回**同一个**错误码 `E6002`，避免账号枚举。
 *
 * 事务口径（铁律 4）：登录会写 `last_login_at`，跨模块组合时用 `login(tx, cmd)` 复用调用方事务；
 * `login(cmd)` 内部仍走 [com.jiancai.db.TxRunner.run]，不存在非事务写入路径。
 * 校验是只读操作，同样提供带 [TxContext] 的重载，便于与其它读取保持同一事务快照。
 */
interface AuthPort {

    /**
     * 在调用方事务内登录。
     *
     * @param tx 由 db-core 的 `TxRunner.run {}` 产生的事务上下文
     * @param cmd 用户名 + 明文密码（只在服务端内存中停留，永不落库）
     * @return 签发结果：token、过期时间（[com.jiancai.shared.dto.WireFormat.TIMESTAMP]）、账号 DTO、契约版本
     * @throws AuthException 账号或密码错误（`E6002`，401）、账号已禁用（`E6004`，403）
     */
    fun login(tx: TxContext, cmd: LoginCmd): LoginResultDto

    /** 便捷登录：内部自开事务（`TxRunner.run {}`），适合只做登录、不与其它写入同事务的场景。 */
    fun login(cmd: LoginCmd): LoginResultDto

    /**
     * 在调用方事务内校验 token。
     *
     * @param token 客户端回传的原始 token（不含 `Bearer ` 前缀，那是 S13 的事）
     * @return 该 token 对应的账号 DTO（S13 用它注入当前操作者）
     * @throws AuthException token 缺失 / 被篡改 / 过期 / 账号已删除（`E401`，401）、账号已禁用（`E6004`，403）
     */
    fun verify(tx: TxContext, token: String): AccountDto

    /** 便捷校验：内部自开事务（`TxRunner.run {}`）；S13 每个请求一次，也可用带 tx 的重载。 */
    fun verify(token: String): AccountDto
}
