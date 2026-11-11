# 代码地图 · fpk-server/serial/src/main/kotlin/com/jiancai/serial

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.serial`：5 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `SerialException.kt` | 取号失败（S07）。 |
| `SerialMigrations.kt` | S07 的迁移（版本号段 100–199，见 `db-core/module.md`）。 |
| `SerialPort.kt` | 单号 Port：S07 唯一对外入口（铁律 8：业务模块只能通过本 Port 取号，禁止自行拼单号）。 |
| `SerialRepository.kt` | `serial_counter` / `serial_number` 的唯一 SQL 出口（铁律 10）。 |
| `SerialService.kt` | [SerialPort] 的唯一实现：事务内取号 + 唯一约束兜底。 |
