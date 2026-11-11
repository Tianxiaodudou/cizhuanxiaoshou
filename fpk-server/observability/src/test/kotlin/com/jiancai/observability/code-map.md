# 代码地图 · fpk-server/observability/src/test/kotlin/com/jiancai/observability

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.observability`：5 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `AuditBoundaryTest.kt` | 硬边界的结构化守卫（S14）： |
| `AuditMigrationsTest.kt` | 迁移与库层约束（版本 700）： |
| `AuditPortTest.kt` | 验收「改价 / 审核有留痕」的正面用例 + 留痕模型的负向用例。 |
| `AuditTransactionTest.kt` | 事务与并发口径（铁律 4）： |
| `TestSupport.kt` | S14 测试夹具：内存库 + 固定时钟 + 一条改价留痕 / 一条审核留痕的样板数据。 |
