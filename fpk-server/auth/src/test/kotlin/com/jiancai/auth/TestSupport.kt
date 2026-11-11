/*
 * @file fpk-server/auth/src/test/kotlin/com/jiancai/auth/TestSupport.kt
 * @role 固定时钟：入参用 UTC `Z` 写法，时间戳按 [TEST_ZONE] 输出。
 * @exports （无）
 * @depends db-core
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.auth

import com.jiancai.db.DbCore
import com.jiancai.db.TxContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/*
 * 测试夹具（不是业务代码）：固定时区 / 时钟 / 密钥 + 少量造数与断言 SQL。
 * 生产 SQL 只允许出现在 AuthRepository（铁律 10）；这里的 SQL 仅用于停用 / 删除账号与断言。
 */

/** 服务端时区：与部署环境一致（token 过期时间与账号时间戳按它输出）。 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 固定签名密钥（32 字节；只用于测试）。 */
internal val TEST_SECRET: ByteArray = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)

/** 另一把密钥：用于「换密钥后旧 token 必须失效」的用例。 */
internal val OTHER_SECRET: ByteArray = "fedcba9876543210fedcba9876543210".toByteArray(Charsets.UTF_8)

/** 固定时钟：入参用 UTC `Z` 写法，时间戳按 [TEST_ZONE] 输出。 */
internal fun clockAt(instant: String): Clock = Clock.fixed(Instant.parse(instant), TEST_ZONE)

/**
 * 组装被测的 [AuthService]。
 *
 * 默认注入低成本哈希（1000 次迭代）让单测跑得快；
 * 生产默认值由 `Pbkdf2PasswordHasher()` 提供，另有专门用例核对默认成本。
 */
internal fun testService(
    db: DbCore,
    clock: Clock = clockAt("2026-11-10T02:00:00Z"),
    secret: ByteArray = TEST_SECRET,
): AuthService = AuthService(
    txRunner = db.txRunner,
    secret = secret,
    clock = clock,
    hasher = Pbkdf2PasswordHasher(iterations = 1_000),
)

internal fun TxContext.exec(sql: String, vararg args: Any?) {
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
        statement.executeUpdate()
    }
}

internal fun TxContext.queryLong(sql: String, vararg args: Any?): Long =
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
        statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
    }

internal fun TxContext.queryString(sql: String, vararg args: Any?): String? =
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
