package com.jiancai.db

/**
 * db-core 连接配置（唯一连接口径）。
 *
 * 除本模块外，任何模块都**不得**自己拼 JDBC URL 或持有 [java.sql.DriverManager]，
 * 需要数据库就调用 Port（[TxRunner] / [Migrator]）。
 *
 * @property path SQLite 路径；默认 [:memory:] 见 [IN_MEMORY]，文件路径的父目录会自动创建。
 * @property busyTimeoutMs 等锁超时（毫秒）；单进程单连接已串行化，此项用于外部进程并发（含迁移工具）时兜底。
 * @property foreignKeys 是否开启外键约束（SQLite 默认关闭，必须显式打开）。
 * @property journalMode 日志模式；[JournalMode.AUTO] 下文件库用 WAL、内存库用 MEMORY。
 */
data class DbConfig(
    val path: String = IN_MEMORY,
    val busyTimeoutMs: Int = DEFAULT_BUSY_TIMEOUT_MS,
    val foreignKeys: Boolean = true,
    val journalMode: JournalMode = JournalMode.AUTO,
) {
    init {
        require(path.isNotBlank()) { "SQLite 路径不能为空" }
        require(busyTimeoutMs >= 0) { "busyTimeoutMs 不能为负（当前：$busyTimeoutMs）" }
    }

    /** 是否为内存库：`jdbc:sqlite::memory:`，随连接关闭而消失。 */
    val isInMemory: Boolean get() = path == IN_MEMORY

    companion object {
        /** 内存库标记（唯一字面量，测试与业务代码都引用它，不另写字符串）。 */
        const val IN_MEMORY: String = ":memory:"

        /** 默认等锁超时 5s：迁移或跨进程写入时避免立刻报 SQLITE_BUSY。 */
        const val DEFAULT_BUSY_TIMEOUT_MS: Int = 5_000

        /** 内存库配置（测试默认）。 */
        fun inMemory(): DbConfig = DbConfig()

        /** 文件库配置；父目录不存在时由 db-core 自动创建。 */
        fun file(path: String): DbConfig = DbConfig(path = path)
    }
}

/**
 * SQLite 的 journal 模式。
 *
 * [AUTO] 是唯一推荐值：文件库 WAL（读写不互相阻塞、崩溃可恢复），内存库 MEMORY（WAL 不支持内存库）。
 */
enum class JournalMode {
    AUTO,
    WAL,
    DELETE,
    TRUNCATE,
    PERSIST,
    MEMORY,
}
