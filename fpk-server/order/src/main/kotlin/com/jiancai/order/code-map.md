# 代码地图 · fpk-server/order/src/main/kotlin/com/jiancai/order

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.order`：7 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `OrderException.kt` | 订单用例失败（S08）。 |
| `OrderMath.kt` | 订单金额派生（内部）：只做「服务端算金额」，不解析单价、不生成单号。 |
| `OrderMigrations.kt` | S08 的迁移（版本号段 400–499，见 `db-core/module.md`；本模块取 400）。 |
| `OrderPort.kt` | 订单 Port：S08 唯一对外入口（建单、改单、当日单、历史查询、应收读取、备注单号代取）。 |
| `OrderPriceRecalculator.kt` | `PriceChanged` 的 S08 侧实现：重算受影响的**当日订单**（验收「改价联动重算」）。 |
| `OrderRepository.kt` | 订单抬头持久化形态（内部类型）：只在 S08 内流转，对外一律转成 [OrderDto]（铁律 2）。 |
| `OrderService.kt` | [OrderPort] 的唯一实现：订单用例层。 |
