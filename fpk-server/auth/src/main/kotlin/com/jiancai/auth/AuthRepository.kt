/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   35 行  类 AuthRepository
 * 第   38 行  函数 findByUsernameKey
 * 第   42 行  函数 findById
 * 第   46 行  函数 findAll
 * 第   56 行  函数 update
 * 第   87 行  函数 deleteById
 * 第   94 行  函数 countAccounts
 * 第  108 行  函数 insert
 * 第  139 行  函数 touchLastLogin
 * 第  148 行  函数 queryOne
 * 第  160 行  函数 toStoredAccount
 * 第  173 行  函数 roleOf
 * 第  177 行  对象 ?
 * 第  220 行  类 StoredAccount
 * 第  232 行  函数 toDto
 */

package com.jiancai.auth

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.enums.Role
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * `auth_account` 的唯一 SQL 出口（铁律 10）：SQL 只出现在这里，其它模块禁止读写本表。
 *
 * 表由 [AuthMigrations] 创建；密码列只存 [PasswordHasher] 产出的哈希，
 * 库层再加一条 `CHECK (password_hash LIKE 'pbkdf2-%')` 兜底（见迁移）。
 */
internal class AuthRepository {

    /** 按规范化用户名（trim + 小写）查找账号；未找到返回 null。 */
    fun findByUsernameKey(tx: TxContext, usernameKey: String): StoredAccount? =
        queryOne(tx, SELECT_BY_USERNAME_KEY) { statement -> statement.setString(1, usernameKey) }

    /** 按主键查找账号；token 校验用它确认账号仍存在。 */
    fun findById(tx: TxContext, id: Long): StoredAccount? =
        queryOne(tx, SELECT_BY_ID) { statement -> statement.setLong(1, id) }

    /** 全部账号，按 id 升序（S03 管理员列表用）。 */
    fun findAll(tx: TxContext): List<StoredAccount> =
        tx.connection.prepareStatement(SELECT_ALL).use { statement ->
            statement.executeQuery().use { rows ->
                val result = ArrayList<StoredAccount>()
                while (rows.next()) result += rows.toStoredAccount()
                result
            }
        }

    /** 更新显示名 / 角色 / 启停；[passwordHash] 为 null 时不动密码列。返回是否有行被更新。 */
    fun update(
        tx: TxContext,
        id: Long,
        displayName: String,
        role: Role,
        enabled: Boolean,
        passwordHash: String?,
        at: String,
    ): Boolean =
        if (passwordHash == null) {
            tx.connection.prepareStatement(UPDATE_PROFILE).use { statement ->
                statement.setString(1, displayName)
                statement.setString(2, role.name)
                statement.setInt(3, if (enabled) ENABLED else DISABLED)
                statement.setString(4, at)
                statement.setLong(5, id)
                statement.executeUpdate() > 0
            }
        } else {
            tx.connection.prepareStatement(UPDATE_PROFILE_AND_PASSWORD).use { statement ->
                statement.setString(1, displayName)
                statement.setString(2, role.name)
                statement.setInt(3, if (enabled) ENABLED else DISABLED)
                statement.setString(4, passwordHash)
                statement.setString(5, at)
                statement.setLong(6, id)
                statement.executeUpdate() > 0
            }
        }

    /** 删除账号；返回是否有行被删除。账号不存在时返回 false（由调用方转 `E6005`）。 */
    fun deleteById(tx: TxContext, id: Long): Boolean =
        tx.connection.prepareStatement(DELETE_BY_ID).use { statement ->
            statement.setLong(1, id)
            statement.executeUpdate() > 0
        }

    /** 账号总数（首次启动引导用：有账号即拒绝再次引导）。 */
    fun countAccounts(tx: TxContext): Long =
        tx.connection.prepareStatement(SELECT_COUNT).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "SELECT COUNT(*) 必须返回一行" }
                rows.getLong(1)
            }
        }

    /**
     * 插入账号并返回主键。
     *
     * 不用 JDBC `getGeneratedKeys`（不同 SQLite 驱动版本行为不一致），
     * 插入后在同一事务内按唯一键回查 id，结果确定。
     */
    fun insert(
        tx: TxContext,
        username: String,
        usernameKey: String,
        displayName: String,
        role: Role,
        passwordHash: String,
        now: String,
    ): Long {
        tx.connection.prepareStatement(INSERT_ACCOUNT).use { statement ->
            statement.setString(1, username)
            statement.setString(2, usernameKey)
            statement.setString(3, displayName)
            // 库层存 Role 枚举名（ADMIN / STAFF），不是线值；线上线值仍由 shared 的 @SerialName 决定。
            statement.setString(4, role.name)
            statement.setInt(5, ENABLED)
            statement.setString(6, passwordHash)
            statement.setString(7, now)
            statement.setString(8, now)
            statement.executeUpdate()
        }
        return tx.connection.prepareStatement(SELECT_ID_BY_USERNAME_KEY).use { statement ->
            statement.setString(1, usernameKey)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "auth_account 插入后未查到账号：$usernameKey" }
                rows.getLong(1)
            }
        }
    }

    /** 记录最近登录时间；登录写操作因此必须落在事务内。 */
    fun touchLastLogin(tx: TxContext, id: Long, at: String) {
        tx.connection.prepareStatement(TOUCH_LAST_LOGIN).use { statement ->
            statement.setString(1, at)
            statement.setString(2, at)
            statement.setLong(3, id)
            statement.executeUpdate()
        }
    }

    private fun queryOne(
        tx: TxContext,
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): StoredAccount? =
        tx.connection.prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toStoredAccount() else null
            }
        }

    private fun ResultSet.toStoredAccount(): StoredAccount = StoredAccount(
        id = getLong("id"),
        username = getString("username"),
        usernameKey = getString("username_key"),
        displayName = getString("display_name"),
        role = roleOf(getString("role")),
        enabled = getInt("enabled") == ENABLED,
        passwordHash = getString("password_hash"),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
        lastLoginAt = getString("last_login_at"),
    )

    private fun roleOf(raw: String): Role =
        Role.values().firstOrNull { it.name == raw }
            ?: error("auth_account.role 非法：$raw（只允许 ADMIN / STAFF）")

    private companion object {
        const val ENABLED = 1
        const val DISABLED = 0

        const val COLUMNS =
            "id, username, username_key, display_name, role, enabled, password_hash, " +
                "created_at, updated_at, last_login_at"

        // 含模板的 SQL 用普通 val（const 初始值只放纯字面量，避免依赖编译器对模板的处理）。
        val SELECT_BY_USERNAME_KEY = "SELECT $COLUMNS FROM auth_account WHERE username_key = ?"

        val SELECT_BY_ID = "SELECT $COLUMNS FROM auth_account WHERE id = ?"

        val SELECT_ALL = "SELECT $COLUMNS FROM auth_account ORDER BY id"

        const val SELECT_ID_BY_USERNAME_KEY = "SELECT id FROM auth_account WHERE username_key = ?"

        const val SELECT_COUNT = "SELECT COUNT(*) FROM auth_account"

        const val INSERT_ACCOUNT =
            "INSERT INTO auth_account " +
                "(username, username_key, display_name, role, enabled, password_hash, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

        const val TOUCH_LAST_LOGIN =
            "UPDATE auth_account SET last_login_at = ?, updated_at = ? WHERE id = ?"

        const val UPDATE_PROFILE =
            "UPDATE auth_account SET display_name = ?, role = ?, enabled = ?, updated_at = ? WHERE id = ?"

        const val UPDATE_PROFILE_AND_PASSWORD =
            "UPDATE auth_account SET display_name = ?, role = ?, enabled = ?, password_hash = ?, " +
                "updated_at = ? WHERE id = ?"

        const val DELETE_BY_ID = "DELETE FROM auth_account WHERE id = ?"
    }
}

/**
 * 账号的持久化形态（内部类型）：只在本模块内流转，**不**作为 Port 出参。
 *
 * 对外一律转成 [AccountDto]（铁律 2），密码哈希字段不会离开本模块。
 */
internal data class StoredAccount(
    val id: Long,
    val username: String,
    val usernameKey: String,
    val displayName: String,
    val role: Role,
    val enabled: Boolean,
    val passwordHash: String,
    val createdAt: String,
    val updatedAt: String,
    val lastLoginAt: String?,
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
