# 代码地图 · shared/src/main/kotlin/com/jiancai/shared/units

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：生产源码根

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.shared.units`：3 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `PackingSpec.kt` | 装箱规格（唯一真源结构）：每个型号一条，由 S05 category 维护。 |
| `PricingUnit.kt` | 计价单位（唯一真源）：件 / 箱 / 袋 / 吨 / 平方 / 米。 |
| `UnitDimension.kt` | 计量维度（唯一真源）：单位换算与打印列头共用。 |
