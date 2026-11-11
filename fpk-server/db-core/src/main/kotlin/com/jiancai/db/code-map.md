# 代码地图 · fpk-server/db-core/src/main/kotlin/com/jiancai/db

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.db`：11 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `DbConfig.kt` | db-core 连接配置（唯一连接口径）。 |
| `DbCore.kt` | db-core 门面：进程内唯一 SQLite 连接的持有者，对外只出两个 Port。 |
| `DbException.kt` | db-core 统一异常基类：所有数据库层错误都从这里派生，便于网关（S13）映射成错误响应。 |
| `Migration.kt` | 一条迁移 = 一个版本号 + 一组语句。 |
| `Migrator.kt` | 迁移 Port：把各模块的 [Migration] 按版本号应用到一个库里，并保证**幂等**。 |
| `SchemaMigrationRepository.kt` | `schema_migration` 的读写包 —— db-core **自己的表、自己的 repository**。 |
| `SqliteDatabase.kt` | 进程内**唯一** SQLite 连接（模块内实现，不对外暴露）。 |
| `SqliteMigrator.kt` | [Migrator] 的唯一实现。 |
| `SqliteTxRunner.kt` | 事务 Port 的唯一实现：单连接 + 可重入锁 + SAVEPOINT 嵌套。 |
| `TxContext.kt` | 事务上下文：跨模块写操作的唯一事务入口，由 [TxRunner.run] 产生并传给各模块 Port 方法。 |
| `TxRunner.kt` | 事务 Port：**所有**写操作的唯一入口（铁律 4）。 |
