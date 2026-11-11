# 代码地图 · fpk-server/db-core/src/test/kotlin/com/jiancai/db

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.db`：4 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `DbCoreTest.kt` | 连接层验收：唯一连接、`:memory:` 可用、PRAGMA 生效、关闭语义、文件库持久化。 |
| `MigratorTest.kt` | 迁移验收：按序应用、幂等、失败原子性、checksum 漂移拦截、内存库可用。 |
| `TestFixtures.kt` | internal fun DbCore.createProbeTables() { |
| `TxRunnerTest.kt` | 事务验收：提交/回滚、嵌套保存点、跨模块同一事务、并发串行化。 |
