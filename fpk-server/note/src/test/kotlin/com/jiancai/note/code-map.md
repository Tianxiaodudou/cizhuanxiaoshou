# 代码地图 · fpk-server/note/src/test/kotlin/com/jiancai/note

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.note`：5 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `NoteBoundaryTest.kt` | 硬边界（S10）的结构化守卫： |
| `NoteMigrationsTest.kt` | S10 迁移与库层约束测试。 |
| `NotePortTest.kt` | S10 创建用例：关联原单、不改原单、走 S07 取号、事务与校验。 |
| `NoteVoidTest.kt` | S10 作废用例：只作废备注单、保留原始数据、不动原单。 |
| `TestSupport.kt` | internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai") |
