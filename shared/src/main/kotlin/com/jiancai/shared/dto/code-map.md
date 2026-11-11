# 代码地图 · shared/src/main/kotlin/com/jiancai/shared/dto

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：生产源码根

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.shared.dto`：13 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `AccountDto.kt` | data class AccountDto( |
| `AuditDto.kt` | 审计留痕条目（1.2.0 追加，S14 observability 产出、S13 只读转发、管理端只展示）。 |
| `BatchTag.kt` | 批号口径（唯一真源）：去首尾空白；比较时 ASCII 大小写不敏感。 |
| `CategoryDto.kt` | 品类 / 型号（唯一出参 DTO，S05 category 产出）。 |
| `Common.kt` | const val DEFAULT_PAGE: Int = 1 |
| `CustomerDto.kt` | 客户（唯一出参 DTO，S04 customer 产出）。 |
| `Money.kt` | 金额（唯一真源，线格式固定为十进制字符串，两位小数，如 `"123.45"`、`"0.00"`、`"-5.00"`）。 |
| `NoteDto.kt` | 差异备注单（唯一出参 DTO，S10 note 产出）。 |
| `OrderDto.kt` | 订单抬头（唯一出参 DTO，S08 order 产出；S09/S10/S11/S12 与 C06/C07/C08 消费）。 |
| `PriceDto.kt` | 恒定价格（唯一出参 DTO，S06 pricing 产出）。 |
| `Quantity.kt` | 数量（唯一真源口径）：十进制字符串，必须 > 0，最多 [WireFormat.QUANTITY_MAX_SCALE] 位小数； |
| `ReturnCheckDto.kt` | 可退批次（S09 产出）：原单上某个「型号 + 批号 + 单位」维度还能退多少。 |
| `SettleDto.kt` | 结算单（唯一出参 DTO，S11 settlement 产出）。 |
