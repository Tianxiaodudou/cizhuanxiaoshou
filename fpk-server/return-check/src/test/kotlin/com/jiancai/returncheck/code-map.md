# 代码地图 · fpk-server/return-check/src/test/kotlin/com/jiancai/returncheck

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.returncheck`：4 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `ReturnCheckBoundaryTest.kt` | 硬边界（S09）的结构化守卫： |
| `ReturnCheckPortTest.kt` | 集成用例：真实 S04 / S05 / S06 / S07 / S08（同一内存库），本模块只经 `OrderPort` 读取。 |
| `ReturnRulesTest.kt` | 规则单测（无数据库）：[ReturnRules] 是纯函数。 |
| `TestSupport.kt` | internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai") |
