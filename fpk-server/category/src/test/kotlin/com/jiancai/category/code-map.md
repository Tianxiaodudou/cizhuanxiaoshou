# 代码地图 · fpk-server/category/src/test/kotlin/com/jiancai/category

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.category`：4 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `CategoryBoundaryTest.kt` | 硬边界（S05）的结构化守卫： |
| `CategoryMigrationsTest.kt` | 迁移验收：号段归属、建表、重复迁移幂等。 |
| `CategoryPortTest.kt` | S05 用例验收：管理员 CRUD、服务端规范化、唯一性、批号类型、装箱规格校验、事务口径。 |
| `TestSupport.kt` | internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai") |
