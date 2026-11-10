package com.jiancai.db

/**
 * db-core 统一异常基类：所有数据库层错误都从这里派生，便于网关（S13）映射成错误响应。
 *
 * 业务错误（缺货、价格已锁等）不属于这里，走 shared 的 `ErrorCode`。
 */
open class DbException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 连接已关闭仍在被使用（通常是 `DbCore.use { }` 作用域写错）。 */
class DbClosedException : DbException("db-core 连接已关闭，不能再执行事务")

/** 单条迁移执行失败：已应用的历史迁移保留，失败迁移的语句整体回滚。 */
class MigrationFailedException(
    val version: Int,
    val name: String,
    cause: Throwable,
) : DbException("迁移 $version（$name）执行失败，已回滚该迁移", cause)

/**
 * 迁移内容漂移：库中记录的 checksum 与当前代码里的迁移不一致。
 *
 * 迁移一经上线即不可修改（只增不改）；出现漂移说明有人改了历史迁移，必须新增一条迁移而不是修改旧迁移。
 */
class MigrationDriftException(
    val version: Int,
    val expected: String,
    val current: String,
) : DbException("迁移 $version 内容已变更：库中 checksum=$expected，代码 checksum=$current；请新增迁移，不要改历史迁移")
