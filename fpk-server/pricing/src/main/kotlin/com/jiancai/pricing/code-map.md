# 代码地图 · fpk-server/pricing/src/main/kotlin/com/jiancai/pricing

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.pricing`：6 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `PriceChanged.kt` | 价格变更事件（S06 产出，S08 order 消费）。 |
| `PricePort.kt` | 定价 Port：S06 唯一对外入口（解析价格、管理员改价、读取改价留痕）。 |
| `PriceRepository.kt` | 价格持久化形态（内部类型）：只在本模块内流转，对外一律转成 [PriceDto]（铁律 2）。 |
| `PriceService.kt` | [PricePort] 的唯一实现：恒定价格的用例层。 |
| `PricingException.kt` | 定价用例失败（S06）。 |
| `PricingMigrations.kt` | S06 的迁移（版本号段 300–399，见 `db-core/module.md`；本模块取 320）。 |
