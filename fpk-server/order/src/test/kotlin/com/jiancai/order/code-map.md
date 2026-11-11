# 代码地图 · fpk-server/order/src/test/kotlin/com/jiancai/order

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.order`：4 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `OrderBoundaryTest.kt` | 硬边界（S08）的结构化守卫： |
| `OrderMigrationsTest.kt` | S08 迁移与库层约束测试。 |
| `OrderPortTest.kt` | S08 订单用例测试（验收：幂等、乐观锁、改当日单、应收派生、改价重算）。 |
| `TestSupport.kt` | internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai") |
