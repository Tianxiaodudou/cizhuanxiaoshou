# 代码地图 · fpk-server/print/src/test/kotlin/com/jiancai/print

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.print`：5 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `PrintBoundaryTest.kt` | 硬边界（S12）的结构化守卫： |
| `PrintMigrationsTest.kt` | S12 迁移与库层约束测试。 |
| `PrintNoteTest.kt` | 备注单打印测试（`PrintPort.render(noteNo)`）。 |
| `PrintPortTest.kt` | S12 打印用例测试（验收：80mm 渲染、五列双行、装箱换算、打印次数）。 |
| `TestSupport.kt` | internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai") |
