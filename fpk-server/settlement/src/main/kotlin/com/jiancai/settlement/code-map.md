# 代码地图 · fpk-server/settlement/src/main/kotlin/com/jiancai/settlement

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.settlement`：6 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `SettleException.kt` | 结算用例失败（S11）。 |
| `SettleMath.kt` | 结算金额派生（内部）：只做「服务端算金额」，不解析单号、不读写表。 |
| `SettlementMigrations.kt` | S11 的迁移（版本号段 500–599 内：S10 用 500，S11 取 510）。 |
| `SettlePort.kt` | 结算 Port：S11 唯一对外入口（提交、审核、已收/未收读模型）。 |
| `SettleRepository.kt` | 结算记录持久化形态（内部类型）：只在 S11 内流转，对外一律转成 [SettleDto]（铁律 2）。 |
| `SettleService.kt` | [SettlePort] 的唯一实现：结算用例层。 |
