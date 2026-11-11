# 代码地图 · fpk-server/pricing/src/test/kotlin/com/jiancai/pricing

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.pricing`：5 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `PricingBoundaryTest.kt` | 硬边界（S06）的结构化守卫： |
| `PricingContractTest.kt` | 契约测试（S06）：本模块的线格式必须与 `shared` 的唯一真源一致。 |
| `PricingMigrationsTest.kt` | S06 迁移与库层硬边界测试。 |
| `PricingPortTest.kt` | S06 定价用例测试（验收：跨日锁、改价留痕、PriceChanged 触发重算）。 |
| `TestSupport.kt` | internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai") |
