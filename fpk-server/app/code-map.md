# 代码地图 · fpk-server/app

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块目录（职责见 module.md）

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

## 文件

| 文件 | 说明 |
| --- | --- |
| `module.md` | 模块说明：职责 / Port / 依赖 / 表归属 / 验收对照 / 变更记录 |
| `code-map.md` | 本目录代码地图（自动生成） |
| `build.gradle.kts` | 组合根：唯一装配 S00–S07 各模块的地方（不属于任何业务模块，也不含业务规则）。 |

## 子目录

| 子目录 | 职责 |
| --- | --- |
| `src/` | 瑙?`fpk-server/app/src/code-map.md` |
