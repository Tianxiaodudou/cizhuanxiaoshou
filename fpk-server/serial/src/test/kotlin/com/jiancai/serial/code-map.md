# 代码地图 · fpk-server/serial/src/test/kotlin/com/jiancai/serial

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.serial`：4 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `SerialConcurrencyTest.kt` | 验收：并发取号不重号。 |
| `SerialMigrationsTest.kt` | 迁移验收：号段归属、建表、重复迁移幂等。 |
| `SerialPortTest.kt` | 验收：前缀与年月日正确、序号口径、事务回滚、唯一约束兜底、序号用尽。 |
| `TestSupport.kt` | internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai") |
