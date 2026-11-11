# 代码地图 · shared/src/test/kotlin/com/jiancai/shared

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：SHARED 契约层：DTO / 枚举 / 错误码 / 单号前缀 / 单位（两端唯一真源）

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.shared`：1 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `ContractTest.kt` | 契约层自检：保证「唯一真源」在 CI 中真的成立，而不是靠人工约定。 |
