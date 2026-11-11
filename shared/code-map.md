# 代码地图 · shared

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：SHARED 契约层：DTO / 枚举 / 错误码 / 单号前缀 / 单位（两端唯一真源）

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

## 文件

| 文件 | 说明 |
| --- | --- |
| `module.md` | 模块说明：职责 / Port / 依赖 / 表归属 / 验收对照 / 变更记录 |
| `code-map.md` | 本目录代码地图（自动生成） |
| `build.gradle.kts` | SHARED · 契约层：两端唯一真源（DTO / 枚举 / 错误码 / 单号前缀 / 单位与装箱规格） |
| `contract.version` | contract.version |

## 子目录

| 子目录 | 职责 |
| --- | --- |
| `src/` | 瑙?`shared/src/code-map.md` |
