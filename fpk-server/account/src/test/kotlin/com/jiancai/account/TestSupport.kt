/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   40 行  函数 testDb
 * 第   43 行  类 CountingTxRunner
 * 第   47 行  函数 run
 * 第   56 行  类 FakeAccountStore
 * 第   58 行  类 FakeAccount
 * 第   68 行  函数 toDto
 * 第   97 行  函数 seed
 * 第  121 行  函数 storedPassword
 * 第  123 行  函数 list
 * 第  128 行  函数 get
 * 第  133 行  函数 create
 * 第  156 行  函数 update
 * 第  170 行  函数 delete
 */

package com.jiancai.account

import com.jiancai.db.DbCore
import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.UpdateAccountCmd
import com.jiancai.shared.enums.Role
import com.jiancai.shared.errors.ErrorCode

/*
 * 测试夹具（不是业务代码）：内存假账号库 + 事务计数。
 *
 * [FakeAccountStore] 是「组合根适配器」的替身：真实实现由 S02 的账号 Port 提供，
 * 这里只模拟它对 S03 的可见契约（重名 E6001、不存在返回 null），不涉及任何 SQL。
 */

internal const val TEST_PASSWORD: String = "staff12345"

/** 打开一个空的内存库（S03 不拥有表，测试不需要迁移）。 */
internal fun testDb(): DbCore = DbCore.testDatabase()

/** 事务计数包装：验证便捷重载确实各开一个事务、带 tx 重载不再自开事务。 */
internal class CountingTxRunner(private val delegate: TxRunner) : TxRunner {
    var runs: Int = 0
        private set

    override fun <T> run(block: (TxContext) -> T): T {
        runs += 1
        return delegate.run(block)
    }
}

/**
 * S02 账号存储的替身：id 自增、用户名 trim + 小写唯一、密码原样暂存（用于断言「S03 不改不哈希」）。
 */
internal class FakeAccountStore : AccountStore {

    private data class FakeAccount(
        val id: Long,
        val username: String,
        val usernameKey: String,
        val displayName: String,
        val role: Role,
        val enabled: Boolean,
        val password: String,
        val createdAt: String,
    ) {
        fun toDto(): AccountDto = AccountDto(
            id = id,
            username = username,
            displayName = displayName,
            role = role,
            enabled = enabled,
            createdAt = createdAt,
        )
    }

    private val accounts = LinkedHashMap<Long, FakeAccount>()
    private var nextId = 1L

    /** 每次调用看到的事务上下文（验证是否复用调用方事务）。 */
    val seenTx: MutableList<TxContext> = mutableListOf()

    var lastCreatedPassword: String? = null
        private set

    var lastUpdatedPassword: String? = null
        private set

    var createCount: Int = 0
        private set

    var updateCount: Int = 0
        private set

    /** 造一个已存在的账号（模拟 S02 引导创建的第一个管理员或历史数据）。 */
    fun seed(
        username: String = "admin",
        displayName: String = "管理员",
        role: Role = Role.ADMIN,
        enabled: Boolean = true,
        password: String = TEST_PASSWORD,
    ): AccountDto {
        val id = nextId++
        val now = "2026-11-10T10:00:00+08:00"
        val account = FakeAccount(
            id = id,
            username = username.trim(),
            usernameKey = username.trim().lowercase(),
            displayName = displayName,
            role = role,
            enabled = enabled,
            password = password,
            createdAt = now,
        )
        accounts[id] = account
        return account.toDto()
    }

    /** 当前库里某账号的明文密码（只用于断言「没改密码」）。 */
    fun storedPassword(id: Long): String? = accounts[id]?.password

    override fun list(tx: TxContext): List<AccountDto> {
        seenTx += tx
        return accounts.values.map { it.toDto() }
    }

    override fun get(tx: TxContext, id: Long): AccountDto? {
        seenTx += tx
        return accounts[id]?.toDto()
    }

    override fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto {
        seenTx += tx
        val usernameKey = cmd.username.trim().lowercase()
        if (accounts.values.any { it.usernameKey == usernameKey }) {
            // 对应 S02 中 username_key 唯一约束的效果（适配器翻译成同一个共享错误码）。
            throw AccountAdminException(ErrorCode.E6001)
        }
        createCount += 1
        val id = nextId++
        accounts[id] = FakeAccount(
            id = id,
            username = cmd.username,
            usernameKey = usernameKey,
            displayName = cmd.displayName,
            role = cmd.role,
            enabled = true,
            password = cmd.password,
            createdAt = "2026-11-10T10:00:00+08:00",
        )
        lastCreatedPassword = cmd.password
        return accounts.getValue(id).toDto()
    }

    override fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto? {
        seenTx += tx
        val current = accounts[cmd.id] ?: return null
        updateCount += 1
        lastUpdatedPassword = cmd.password
        accounts[cmd.id] = current.copy(
            displayName = cmd.displayName,
            role = cmd.role,
            enabled = cmd.enabled,
            password = cmd.password ?: current.password,
        )
        return accounts.getValue(cmd.id).toDto()
    }

    override fun delete(tx: TxContext, id: Long): Boolean {
        seenTx += tx
        return accounts.remove(id) != null
    }
}
