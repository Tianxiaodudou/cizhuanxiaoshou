/*
 * @file fpk-server/auth/src/main/kotlin/com/jiancai/auth/AuthService.kt
 * @role [AuthPort] 与 [AccountStorePort] 的唯一实现：账号密码登录 + 服务端签名 token 的签发与校验 + 账号 CRUD。
 * @exports AuthService
 * @depends db-core, shared
 * @calledBy fpk-server/app/src/main/kotlin/com/jiancai/app/App.kt
 * @sideEffects 经本模块 repository 读写（SQL 收口在 repository）
 */

package com.jiancai.auth

import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.LoginCmd
import com.jiancai.shared.dto.LoginResultDto
import com.jiancai.shared.dto.UpdateAccountCmd
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.enums.Role
import com.jiancai.shared.errors.ErrorCode
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * [AuthPort] 与 [AccountStorePort] 的唯一实现：账号密码登录 + 服务端签名 token 的签发与校验 + 账号 CRUD。
 *
 * 登录流程（全部在事务内完成）：
 * 1. 用户名规范化（trim + 小写）后查 `auth_account`；
 * 2. 账号不存在时仍跑一次 PBKDF2 校验，抹平「有无此账号」的响应时间差；
 * 3. 密码错误 / 账号不存在统一抛 `E6002`（同一文案，避免枚举）；
 * 4. 密码正确但账号停用 → `E6004`；
 * 5. 签发 token（HMAC-SHA256 + 服务端时钟过期时间），落 `last_login_at`，返回 [LoginResultDto]。
 *
 * token 校验流程（只读）：验签 → 校验过期 → 回库核对账号仍存在且启用 → 返回 [AccountDto]。
 * token 是无状态签名串（不在库里留副本）；停用账号后其旧 token 立即失效，因为每次校验都回库核对。
 *
 * @param txRunner db-core 的事务 Port（铁律 4：登录写操作必须走它）
 * @param secret token 签名密钥，**由部署方提供**（≥32 字节），禁止硬编码 / 默认值
 * @param clock 服务端时钟；token 过期时间与时间戳格式均以它为准，测试注入固定时钟
 * @param tokenTtl token 有效期，默认 [DEFAULT_TOKEN_TTL]
 * @param hasher 密码哈希策略，默认 [Pbkdf2PasswordHasher]；测试可注入更低成本参数
 */
class AuthService(
    private val txRunner: TxRunner,
    secret: ByteArray,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val tokenTtl: Duration = Duration.ofHours(DEFAULT_TOKEN_TTL_HOURS.toLong()),
    private val hasher: PasswordHasher = Pbkdf2PasswordHasher(),
) : AuthPort, AccountStorePort {

    private val signer = TokenSigner(secret)

    private val repository = AuthRepository()

    /** 账号不存在时用来「陪跑」一次哈希校验，避免按响应时间枚举账号。 */
    private val dummyHash: String = hasher.hash(DUMMY_PASSWORD)

    /** 线格式时间戳（唯一真源 `WireFormat.TIMESTAMP`）；按服务端时区输出。 */
    private val wireTimestamps: DateTimeFormatter =
        DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP).withZone(clock.zone)

    init {
        require(!tokenTtl.isZero && !tokenTtl.isNegative) {
            "token 有效期必须为正（当前：$tokenTtl）"
        }
    }

    override fun login(cmd: LoginCmd): LoginResultDto = txRunner.run { tx -> login(tx, cmd) }

    override fun login(tx: TxContext, cmd: LoginCmd): LoginResultDto {
        val usernameKey = normalizeUsername(cmd.username)
        if (usernameKey.isEmpty() || cmd.password.isEmpty()) {
            // 空账号 / 空密码与「密码错误」同码同文案：不向调用方区分输入问题与账号是否存在。
            hasher.verify(cmd.password, dummyHash)
            throw AuthException("用户名或密码错误", ErrorCode.E6002)
        }

        val account = repository.findByUsernameKey(tx, usernameKey)
        if (account == null) {
            hasher.verify(cmd.password, dummyHash)
            throw AuthException("用户名或密码错误", ErrorCode.E6002)
        }
        if (!hasher.verify(cmd.password, account.passwordHash)) {
            throw AuthException("用户名或密码错误", ErrorCode.E6002)
        }
        // 密码正确才提示「已禁用」：错误密码的调用方不应拿到账号状态。
        if (!account.enabled) {
            throw AuthException("账号已禁用", ErrorCode.E6004)
        }

        val now = clock.instant()
        val expiresAt = now.plus(tokenTtl)
        val token = signer.issue(account.id, now, expiresAt)
        repository.touchLastLogin(tx, account.id, wireTimestamps.format(now))

        return LoginResultDto(
            token = token,
            expiresAt = wireTimestamps.format(expiresAt),
            account = account.toDto(),
        )
    }

    override fun verify(token: String): AccountDto = txRunner.run { tx -> verify(tx, token) }

    override fun verify(tx: TxContext, token: String): AccountDto {
        val claims = signer.verify(token, clock.instant())
            ?: throw AuthException("登录已失效，请重新登录", ErrorCode.E401)
        // 不信任 token 里的身份：每次校验都回库核对账号仍存在（停用 / 删除立即生效）。
        val account = repository.findById(tx, claims.accountId)
            ?: throw AuthException("登录已失效，请重新登录", ErrorCode.E401)
        if (!account.enabled) {
            throw AuthException("账号已禁用", ErrorCode.E6004)
        }
        return account.toDto()
    }

    // —— AccountStorePort：S03 管理员用例层的账号读写（SQL 仍只走 AuthRepository，密码仍只经 hasher） ——

    override fun list(tx: TxContext): List<AccountDto> = repository.findAll(tx).map { it.toDto() }

    override fun get(tx: TxContext, id: Long): AccountDto? = repository.findById(tx, id)?.toDto()

    override fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto =
        create(tx, cmd.username, cmd.password, cmd.displayName, cmd.role).toDto()

    override fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto? {
        // 「不存在」优先于「入参不合法」：调用方据此统一转 E6005，不因输入问题改变存在性语义。
        if (repository.findById(tx, cmd.id) == null) return null
        val displayName = cmd.displayName.trim()
        if (displayName.isEmpty()) throw AuthException("显示名不能为空", ErrorCode.E422)
        // password 为 null / 空白表示不改密码（与 shared 契约及 S03 的归一化一致）；
        // 给了新密码就必须满足 S02 的策略。
        val passwordHash = cmd.password?.takeIf { it.isNotBlank() }?.let { raw ->
            if (raw.length < MIN_PASSWORD_LENGTH) {
                throw AuthException("密码至少 $MIN_PASSWORD_LENGTH 位", ErrorCode.E422)
            }
            hasher.hash(raw)
        }
        val updated = repository.update(
            tx = tx,
            id = cmd.id,
            displayName = displayName,
            role = cmd.role,
            enabled = cmd.enabled,
            passwordHash = passwordHash,
            at = wireTimestamps.format(clock.instant()),
        )
        return if (updated) repository.findById(tx, cmd.id)?.toDto() else null
    }

    override fun delete(tx: TxContext, id: Long): Boolean = repository.deleteById(tx, id)

    /**
     * 首次启动引导：仅在**库中还没有任何账号**时创建第一个管理员。
     *
     * 这不是日常账号 CRUD（那属于 S03 account），只为让新部署在不预置默认密码的前提下可以登录。
     * 已有账号时抛 `E6001`，因此不会成为线上改密 / 提权的后门。
     *
     * @return 新建的账号 DTO（密码不入 DTO）
     * @throws AuthException `E6001` 已存在账号、`E422` 用户名 / 显示名为空或密码过短
     */
    fun bootstrapFirstAdmin(username: String, password: String, displayName: String): AccountDto =
        txRunner.run { tx ->
            if (repository.countAccounts(tx) > 0) {
                throw AuthException("已存在账号，首次启动引导不再可用", ErrorCode.E6001)
            }
            val account = create(tx, username, password, displayName, Role.ADMIN)
            account.toDto()
        }

    /** 统一的新建账号路径（用户名规范化、密码策略、唯一性检查都在这里）。 */
    private fun create(
        tx: TxContext,
        username: String,
        password: String,
        displayName: String,
        role: Role,
    ): StoredAccount {
        val trimmedUsername = username.trim()
        val usernameKey = normalizeUsername(username)
        val trimmedDisplayName = displayName.trim()
        if (trimmedUsername.isEmpty()) throw AuthException("用户名不能为空", ErrorCode.E422)
        if (trimmedDisplayName.isEmpty()) throw AuthException("显示名不能为空", ErrorCode.E422)
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw AuthException("密码至少 $MIN_PASSWORD_LENGTH 位", ErrorCode.E422)
        }
        if (repository.findByUsernameKey(tx, usernameKey) != null) {
            throw AuthException("账号已存在", ErrorCode.E6001)
        }

        val now = clock.instant()
        val nowText = wireTimestamps.format(now)
        val passwordHash = hasher.hash(password)
        val id = try {
            repository.insert(
                tx = tx,
                username = trimmedUsername,
                usernameKey = usernameKey,
                displayName = trimmedDisplayName,
                role = role,
                passwordHash = passwordHash,
                now = nowText,
            )
        } catch (failure: SQLException) {
            // 唯一索引兜底（并发建同名账号）：翻译成共享错误码，不把 SQL 细节漏给调用方。
            if (isUniqueViolation(failure)) throw AuthException("账号已存在", ErrorCode.E6001)
            throw failure
        }
        return StoredAccount(
            id = id,
            username = trimmedUsername,
            usernameKey = usernameKey,
            displayName = trimmedDisplayName,
            role = role,
            enabled = true,
            passwordHash = passwordHash,
            createdAt = nowText,
            updatedAt = nowText,
            lastLoginAt = null,
        )
    }

    /** 登录大小写不敏感：规范化键与库中 `username_key` 同口径。 */
    private fun normalizeUsername(username: String): String = username.trim().lowercase()

    /** 唯一约束冲突（SQLite `UNIQUE constraint failed...`）：只用于把并发重名翻译成 `E6001`。 */
    private fun isUniqueViolation(failure: SQLException): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            if (current is SQLException && current.message?.contains("UNIQUE", ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }

    companion object {
        /** token 默认有效期（小时）：12 小时 = 一个工作班次，过期后必须重新登录。 */
        const val DEFAULT_TOKEN_TTL_HOURS: Int = 12

        /** [DEFAULT_TOKEN_TTL_HOURS] 对应的 `Duration`（文档 / 组合根校验用）。 */
        val DEFAULT_TOKEN_TTL: Duration = Duration.ofHours(DEFAULT_TOKEN_TTL_HOURS.toLong())

        /** 新建账号的最小密码长度。 */
        const val MIN_PASSWORD_LENGTH: Int = 8

        /** 「陪跑」哈希用的占位密码（永不作为真实账号密码）。 */
        private const val DUMMY_PASSWORD = "jiancai-auth-timing-equalizer"
    }
}
