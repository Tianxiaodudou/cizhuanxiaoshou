# 代码地图 · fpk-server/customer/src/main/kotlin/com/jiancai/customer

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.customer`：5 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `CustomerException.kt` | 客户用例失败（S04）。 |
| `CustomerMigrations.kt` | S04 的迁移（db-core 分配给业务主链 300–399，见 `db-core/module.md`）。 |
| `CustomerPort.kt` | 客户 Port：S04 唯一对外入口（建档、按电话查询、按主键读取）。 |
| `CustomerRepository.kt` | `customer` 表的唯一 SQL 出口（铁律 10）。 |
| `CustomerService.kt` | [CustomerPort] 的唯一实现：客户建档与查询的用例层。 |
