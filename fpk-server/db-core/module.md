# S00 · db-core

> contractVersion = **1.0.0**（依赖 `shared` 的契约版本；本模块不新增跨端线格式）

## 职责

进程内**唯一** SQLite 连接、事务、迁移、`:memory:` 测试库。
只做数据库内核四件事，不含任何业务逻辑、不建任何业务表、不生成单号。

写入范围：`fpk-server/db-core/**`（本模块是唯一允许出现 `DriverManager` / JDBC URL 的模块）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `DbCore.kt` | 门面：唯一连接持有者 + 工厂（`open` / `inMemory` / `testDatabase`） |
| `DbConfig.kt` | 连接配置与 `JournalMode`（路径、busy_timeout、外键、WAL） |
| `SqliteDatabase.kt` | 内部：单连接 + 可重入锁 + PRAGMA 初始化（**不对外**） |
| `TxRunner.kt` / `TxContext.kt` | **Port**：事务入口与事务上下文 |
| `SqliteTxRunner.kt` | 内部：真实事务 + SAVEPOINT 嵌套 + 串行化 |
| `Migrator.kt` / `Migration.kt` | **Port**：迁移接口、迁移定义、报告与漂移诊断 |
| `SqliteMigrator.kt` | 内部：按序应用 + 幂等 + checksum 漂移拦截 |
| `SchemaMigrationRepository.kt` | 内部：`schema_migration` 账本表的**唯一** SQL 出口 |
| `DbException.kt` | `DbException` / `DbClosedException` / `MigrationFailedException` / `MigrationDriftException` |

## 本模块实现的 Port

```kotlin
interface TxRunner {
    fun <T> run(block: (TxContext) -> T): T          // 跨模块写操作的唯一包裹方式
}

class TxContext internal constructor(
    val connection: java.sql.Connection,             // 只给各模块自己的 repository 用
    val depth: Int,                                  // 1 = 最外层事务，>1 = 保存点
) { val isRoot: Boolean }

interface Migrator {
    fun migrate(): MigrationReport                   // 幂等：重复调用不产生副作用
    fun currentVersion(): Int
    fun pendingVersions(): List<Int>
    fun drift(): List<MigrationDrift>
}

data class Migration(val version: Int, val name: String, val statements: List<String>)
```

组合根接入（服务启动一次）：

```kotlin
val db = DbCore.open(DbConfig.file("/var/lib/jiancai/jiancai.db"), migrations) // 各模块迁移合并后传入
db.migrator.migrate()                // 启动即迁移，幂等；失败即启动失败（不允许带病启动）
db.txRunner.run { tx ->              // 跨模块写 = 一次调用、一个事务
    orderPort.create(tx, cmd)        // S08
    settlementPort.record(tx, cmd)   // S11
}
```

各模块 repository 只接收 `TxContext`（`tx.connection`）执行自己的 SQL；Port 方法签名统一带 `tx` 参数。

## 事务语义（验收 1：跨模块写可被同一事务包裹）

- **单连接 + 可重入锁**：所有写事务串行化，不会出现 `SQLITE_BUSY`，也不会丢更新；
- **嵌套**：同线程再次 `run` 复用同一事务并用 `SAVEPOINT dbcore_sp_<深度>`；内层失败只回滚内层，
  异常继续上抛；最外层失败整体回滚——因此「A 模块写入成功、B 模块失败」不会留下半条数据；
- **不会提前提交**：Port 内层调用没有独立 `commit`，提交只发生在最外层；
- 禁止在事务块内起线程并等待（单连接会自锁）；跨线程提交请各自 `run`。

## 迁移规则（验收 2：迁移幂等）

- **只增不改**：已上线的迁移一个字都不能改；改表只能新增更高版本号；
- **幂等**：账本表 `schema_migration`（`version` 主键）记录已应用版本与 checksum，
  `migrate()` 重复调用只核对、不重复执行（重启、并发启动同样成立）；
- **一迁移一事务**：第 N 条失败只回滚第 N 条，N-1 之前保持已应用（SQLite DDL 可回滚）；
- **漂移拦截**：库中 checksum 与代码不符 → `MigrationDriftException`，拒绝继续而非静默重跑；
- 语句粒度：`statements` 每条**只写一条 SQL**（db-core 不拆 `;`，避免字符串里的分号被切断）。

### 版本号段位（避免各模块抢号，待确认）

| 段位 | 归属 |
| --- | --- |
| 1–99 | db-core（地基 / `schema_migration` 扩展） |
| 100–199 | S07 serial |
| 200–299 | S01 config、S02 auth、S03 account |
| 300–399 | S04 customer、S05 category、S06 pricing |
| 400–499 | S08 order、S09 return-check |
| 500–599 | S10 note、S11 settlement |
| 600–699 | S12 print |
| 700–799 | S14 observability（审计台账，迁移 700） |
| 800–899 | S13 api-gateway（幂等台账，迁移 800） |

版本号重复在 `SqliteMigrator` 构造期直接 `require` 失败（不会跑到线上才发现）。

## `:memory:` 测试库（验收 3）

```kotlin
DbCore.testDatabase(listOf(myMigration)).use { db ->      // 内存库 + 立即迁移
    db.txRunner.run { tx -> ... }
}
DbCore.inMemory()                                          // 只要事务机制、不要表
```

- 每个 `DbCore.inMemory()` 是**独立**内存库（连接即库），测试之间天然隔离；
- 内存库 `journal_mode` 退化为 `MEMORY`（SQLite 不支持内存库 WAL），文件库默认 `WAL`；
- 内存库随 `close()` 销毁，无需清理临时文件。

## 硬边界（禁止）

- **无业务逻辑**：不建业务表、不做金额/单号计算、不判断权限；
- **禁止其它模块自开连接**：不得 `DriverManager.getConnection`、不得反射拿 `SqliteDatabase`、
  不得缓存或跨线程传递 `TxContext`；
- **SQL 只在 repository**：db-core 内部只有 `SchemaMigrationRepository` 出现 SQL；
- **不得自行 commit/rollback**：事务边界只属于 `TxRunner`（`tx.connection` 仅用于执行 SQL）。

## 验收对照

- [x] 跨模块写操作可被同一事务包裹：`TxRunnerTest.cross_module_writes_are_wrapped_in_one_transaction`
      （成功一起提交、失败一起回滚）、`outer_rollback_discards_inner_joined_work`（内层不提前提交）、
      `nested_run_uses_a_savepoint_and_keeps_outer_writes`（内层失败只回滚内层）。
- [x] 迁移幂等：`MigratorTest.migrate_is_idempotent`（第二次 `applied` 为空、账本仍 2 行）、
      文件库重开后 `applied` 为空（`DbCoreTest.file_database_persists_wal_and_creates_parent_directory`）。
- [x] `:memory:` 测试库可用：`DbCoreTest.in_memory_database_accepts_reads_and_writes`、
      `MigratorTest.memory_database_is_a_valid_migration_target`、
      `DbCoreTest.test_database_is_migrated_in_memory_target`。
- [x] 单连接与并发串行化：`one_instance_has_exactly_one_connection`、
      `concurrent_writers_are_serialized_on_the_single_connection`（4 线程 × 25 次，计数精确）。

## 集成前置（当前仓库尚缺，与 shared 相同）

本仓库还没有根工程（`settings.gradle.kts` / 根 `build.gradle.kts`），也还没有 `fpk-server` 聚合工程：

```kotlin
// 根 settings.gradle.kts
include(":shared", ":fpk-server", ":fpk-server:db-core")

// 根 build.gradle.kts
plugins {
    kotlin("jvm") version "<kotlin 版本>" apply false
    kotlin("plugin.serialization") version "<kotlin 版本>" apply false
}
```

`jvmToolchain(17)` 需要本机 JDK 17（或根工程配 foojay-resolver 自动下载）；
`sqliteJdbcVersion` 可在 `gradle.properties` 覆盖（默认 `3.46.1.3`）。
上述前置未补齐前 `:fpk-server:db-core:test` 无法运行——这是工程装配问题，不是模块内部问题。

## 待确认的落地假设

1. 包名 `com.jiancai.db`（沿用 `com.jiancai` 前缀，与服务端其它模块一致）。
2. 驱动选型 `org.xerial:sqlite-jdbc`（JVM 原生、无需 natives 打包步骤），以 `runtimeOnly` 引入，
   其它模块的编译类路径上看不到 `org.sqlite`，从依赖侧堵住「自行开连接」。
3. `schema_migration` 表名与列（`version/name/checksum/applied_at/took_ms`）由 db-core 独占，
   业务模块不得读写；`applied_at` 为 UTC ISO-8601（`Instant.toString()`）。
4. 迁移版本号段位见上表，若集成时改分配方案，只影响号段约定，不影响已落库版本号。
5. 事务内不做重试/排他升级：写冲突由单连接锁在进程内消解，跨进程并发依赖 `busy_timeout`（默认 5s）。
