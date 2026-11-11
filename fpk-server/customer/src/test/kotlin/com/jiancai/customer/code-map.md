# 代码地图 · fpk-server/customer/src/test/kotlin/com/jiancai/customer

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.customer`：4 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `CustomerBoundaryTest.kt` | 硬边界（S04）的结构化守卫： |
| `CustomerContractTest.kt` | 契约测试（S04）：本模块读写的线格式必须与 `shared` 的唯一真源一致。 |
| `CustomerPortTest.kt` | 验收：客户建档、按电话查询、地址快照来源。 |
| `TestSupport.kt` | internal const val NOW_INSTANT: String = "2026-11-11T02:00:00Z" |
