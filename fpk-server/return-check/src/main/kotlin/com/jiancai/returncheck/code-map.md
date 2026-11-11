# 代码地图 · fpk-server/return-check/src/main/kotlin/com/jiancai/returncheck

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.returncheck`：5 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `ReturnCheckException.kt` | 退单校验失败（S09）。 |
| `ReturnCheckPolicy.kt` | 退单校验策略（S09 的**唯一**规则开关，独立于 Port 与读取路径，可整体替换升级）。 |
| `ReturnCheckPort.kt` | 退单校验 Port：S09 唯一对外入口（可退批次查询、退单申请校验）。 |
| `ReturnCheckService.kt` | 退单校验实现（S09）：只做「读 S08 + 交给规则」的编排，不含任何业务口径。 |
| `ReturnRules.kt` | 批次维度键：型号 + 批号比较键（shared 的 `BatchTagRules.comparisonKey`）+ 单位。 |
