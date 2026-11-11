# S12 · print

> contractVersion = **1.0.0**（只依赖 `shared` 契约；打印版式是本模块私有实现，不进 `shared`）

## 职责

服务端打印渲染：**80mm 热敏纸、五列 / 双行、装箱换算、打印次数**。
客户端只请求打印 / 展示「已打印几次」，不做任何排版、不直连打印机（铁律 6）。

写入范围：`fpk-server/print/**`（本模块独占 `print_job` 一张表）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `PrintPort.kt` | **Port**：`render(docNo)` / `render(tx, docNo)`，返回 `PrintDocumentDto` |
| `PrintService.kt` | 实现：查单据（S08 → 备注单读 Port）→ 判次数 → 渲染 → 落台账，全程一个事务 |
| `PrintRepository.kt` | 内部：`print_job` 的**唯一** SQL 出口（原子「判上限 + 落一行」+ 计数） |
| `PrintMigrations.kt` | 迁移：版本 600 建 `print_job` + `doc_no` 索引（交给 db-core 的 `Migrator`） |
| `PrintRenderer.kt` | 纯渲染：DTO → 80mm 文本 + 结构化视图 |
| `PrintLayout.kt` | 版式常量：80mm / 72mm / 48 半角格 / 五列宽度 / 双行规则 |
| `PrintText.kt` | 显示宽度、截断、填充、折行（中文按 2 格，唯一实现） |
| `PrintConversion.kt` | 装箱换算：数量 ↔ 包装数、重量 / 体积（只在服务端） |
| `PrintDocumentDto.kt` | 出参 DTO：抬头 / 明细 / 合计 / 备注 / 完整文本 |
| `NoteReadPort.kt` | S12 消费侧声明的备注单只读最小契约（见「备注单」） |
| `PrintException.kt` | `E7001` / `E7002` / `E422` |

## 本模块实现的 Port

```kotlin
interface PrintPort {
    // 跨模块写：与调用方其它写入同一个事务
    fun render(tx: TxContext, docNo: String, printedBy: Long? = null): PrintDocumentDto
    // 独立打印：内部仍走 TxRunner.run {}，不存在非事务路径
    fun render(docNo: String, printedBy: Long? = null): PrintDocumentDto
}
```

`docNo` 是**订单号或备注单号**：先经 S08 `OrderPort.getRecent(OrderQuery(orderNo = ...))` 查订单，
查不到再经 `NoteReadPort` 查备注单。**不截取 / 猜测单号前缀**（铁律 8），两者都查不到抛 `E7001`。

出参是 `PrintDocumentDto`（DTO，不返回 Entity）：其中 `content` 就是送打印机的文本，
`items` / `totals` / `note` 是同一份数据的结构化视图，`printCount` 是**本次打印后**的累计次数。

## 80mm 版式（五列 / 双行）

- 纸宽 **80mm**，可打印区 **72mm**，每行 **48 个半角格**（中文 / 全角标点按 2 格）；
- **五列**：品名（含型号）/ 规格 / 数量 / 单价 / 金额，列宽 `15 + 8 + 8 + 8 + 9 = 48`；
  文本列左对齐，数字列右对齐，超宽按整字截断（不会切出半个中文）；
- **双行**：每条明细固定两行——
  - 第一行：五列；
  - 第二行：批号 / 换算 / 重量 / 体积（没有内容时留空，保持两行占位）；
- 表头同样两行：第一行五列列名，第二行「批号 / 换算 / 重量」；
- 抬头：商家名称（S01，居中）、单据标题（`DocType.label`，居中）、单号、日期、客户、电话、地址、
  状态、原单号、备注；长地址 / 备注按显示宽度折行，续行与值对齐；
- 页脚：打印次数、补打标记、打印时间、打印人。

渲染示例（实际输出为等宽文本，行宽 ≤ 48 半角格）：

```text
                 建材商行
                  采购单
单号: PS202611110001
日期: 2026-11-11
客户: 张三
电话: 13800000000
地址: 建材市场 1 号
状态: 已下单
------------------------------------------------
      品名           规格   数量    单价     金额
批号 / 换算 / 重量
------------------------------------------------
1.瓷砖 800x800   800×800   25件   12.30   307.50
  批号 A  换算 2箱+5件  重 26.25kg  体积 0.05m³
------------------------------------------------
合计:       307.50
应收:       307.50
已收:         0.00
------------------------------------------------
打印次数: 1
打印时间: 2026-11-11T10:00:00+08:00
打印人: #42
```

`PrintDocumentDto` 同时给出 `paperWidthMm = 80`、`printableWidthMm = 72`、
`charactersPerLine = 48`、`columns = 5`、`linesPerItem = 2`，测试与 S13 回执可直接断言这些口径。

## 换算（只在服务端，客户端零换算）

数据源是订单明细里保存的装箱规格**快照**（`LineDto.packingSpec`），不重新读 S05，
保证补打旧单仍按下单当时的规格显示。

- 仅当 `packingSpec.baseUnit == line.unit` 且单位是**计数型**（件 / 箱 / 袋）时换算；
  重量 / 面积 / 长度型单位的包装换算没有契约定义（`unitsPerPackage` 是整数，表达不了 1.44㎡/箱），
  本模块不猜测，直接不显示；
- 包装数 = 数量 ÷ `unitsPerPackage`：
  - 整除：`20 件 / 10 = 2箱`；
  - 有零头：`25 件 / 10 = 2箱+5件`；
  - 不足一箱：`5 件 / 10 = 0.5箱`；
- 重量 / 体积 = 包装数 × 每包装重量 / 体积，最多 3 位小数、去掉尾零（`26.250` → `26.25`）；
- 换算只影响**打印展示**，不重算、不回写订单金额（金额仍来自 S08，铁律 5）。

## 打印次数

每次成功 `render` 向 `print_job` 落一行；`COUNT(*)` 就是该单据的打印次数。

- **原子判上限**：`INSERT ... SELECT ... WHERE (SELECT COUNT(*)) < ?` 一条语句完成
  「检查 + 落台账」，不会先查后插出现超打；
- **上限**：默认每单 **3 次**（首打 + 2 次补打），可由组合根 `maxPrintsPerDocument` 覆盖；
  达到上限抛 `E7002`，且不写新行；
- **事务**：次数写入与渲染在调用方同一个 `TxRunner` 事务内；渲染失败 / 调用方回滚时台账一并回滚，
  不会出现「次数加了、票没出」；
- **持久化**：次数在库中，重启 / 换实例不归零；
- **留痕**：`print_job` 记录 `doc_type`、`paper_width_mm`、`template_version`、`printed_at`、
  `printed_by`；不存渲染正文（避免客户电话 / 地址落库）。

## 备注单（`render(noteNo)`）

备注单内容来自 S10。S10 当前只暴露 `create` / `void`，没有读方法；按「接口只增不改」，
S12 在消费侧声明最小只读契约 `NoteReadPort.get(tx, noteNo): NoteDto?`：

```kotlin
fun interface NoteReadPort {
    fun get(tx: TxContext, noteNo: String): NoteDto?
}
```

组合根用薄适配器接上（S10 追加 `get` 后适配器只有一行）；未装配（`notes = null`）时订单打印不受影响，
备注单号按 `E7001` 返回。备注单打印只输出备注抬头 + 差异类型 / 金额 / 备注 / 作废原因，
**不重复打印原单明细**；原单只用于带出客户快照，原单查不到时仍打印备注单自身字段。

## 迁移（版本号段 600–699，本模块取 600）

```kotlin
DbCore.open(config, PrintMigrations.all + otherModules)
```

| 版本 | 名称 | 内容 |
| --- | --- | --- |
| 600 | `create_print_job_table` | `print_job`（自增 id、`doc_no`、`doc_type`、`paper_width_mm`、`template_version`、`printed_at`、`printed_by`，全部带 CHECK）+ `idx_print_job_doc_no` |

迁移只增不改；需要改表时追加 601、602…（db-core 的 checksum 漂移检测会拦住改历史迁移）。

## 依赖与边界

- 依赖：`shared`、db-core（`TxRunner` / `TxContext` / `Migrator` / `Migration`）、
  S08 `OrderPort`（只读）、S01 `ConfigPort`、`NoteReadPort`（消费侧最小读契约）。
- 组合根（服务启动一次）：

```kotlin
val print: PrintPort = PrintService(
    txRunner = db.txRunner,
    orders = orderPort,          // S08
    config = configPort,         // S01
    notes = noteReadAdapter,     // S10 读适配器；未提供时传 null
    clock = Clock.systemDefaultZone(),
    maxPrintsPerDocument = 3,
)
```

- 根 `settings.gradle.kts` 需补 `include(":fpk-server:print")`（本模块写入范围不含根工程文件）。
- 禁止：客户端打印 / 客户端排版；直接读写订单表 / 备注表；自行拼单号；在
  `PrintRepository` 之外写 SQL；本模块判断权限（权限只在 S13）。
- **客户端 deny 的 S12 侧**：本模块不依赖 Android / HTTP 客户端，打印入口只有服务端 Port；
  客户端全局 deny 打印关键词的 hook 属于 C12 / SCAN 模块，不在本模块写入范围。

## 验收对照

本机已用 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:print:test`：**25 个用例全部通过（0 失败 / 0 跳过）**
（`PrintPortTest` 9 + `PrintNoteTest` 5 + `PrintMigrationsTest` 4 + `PrintBoundaryTest` 7）。
验证用临时根工程在 `.tools/verify-print/`（已 gitignore）；真实根工程补上
`include(":fpk-server:print")` 后可直接跑同一任务。

- [x] 80mm 渲染正确：`PrintPortTest.order_renders_the_80mm_five_column_two_line_layout_with_server_produced_values`
      （80mm / 72mm / 48 格；每一行 `displayWidth <= 48`；商家名、单号、客户、金额、页脚齐全）。
- [x] 五列 / 双行：同一用例断言 `columns = 5`、`linesPerItem = 2`、五列列名与明细两行；
      `exact_package_quantities_show_whole_packages_without_a_remainder` 覆盖整箱换算。
- [x] 换算：`2箱+5件` / `2箱` / 重量 `26.25kg` / 体积 `0.05m³`；
      `a_model_without_packing_spec_prints_without_conversion` 覆盖无规格不显示换算。
- [x] 打印次数记录：`print_count_is_persisted_across_service_instances_and_marks_reprints`
      （换实例继续计数、补打标记、第 4 次 `E7002`）；
      `the_configured_print_limit_returns_e7002_without_writing_a_row`（上限可配、超限不写行）；
      `render_reuses_the_callers_transaction_and_rolls_back_the_print_count`（回滚不扣次数）。
- [x] 客户端 deny 生效（S12 侧）：`PrintBoundaryTest.the_module_has_no_client_side_print_dependency`
      （不依赖 Android / HTTP 客户端；打印只在服务端）。
- [x] 禁止直接访问 order 表：`PrintBoundaryTest.sql_only_lives_in_the_print_repository`、
      `the_module_never_touches_order_or_note_tables`、`the_module_owns_exactly_one_business_table`。
- [x] 备注单：`PrintNoteTest` 5 个用例（读 Port 渲染、作废标记、原单缺失、未知单号、未装配读 Port）。

## 待确认的落地假设（v1.0.0 基线，如有异议按契约流程调整）

1. **五列口径**：品名（含型号）/ 规格 / 数量 / 单价 / 金额；双行 = 第一行五列、第二行批号 / 换算 / 重量 / 体积。
   若业务要求「序号」独立成列或第二行放其它字段，只改 `PrintLayout`，Port 不变。
2. **48 格**：按 80mm 纸、12×24 点阵字体估算；若部署的打印机是 58mm 或字体不同，改
   `PrintLayout.CHARACTERS_PER_LINE` / 列宽即可，`PrintDocumentDto.charactersPerLine` 会同步。
3. **打印上限默认 3 次**：`E7002` 已由共享错误码定义，但上限值没有契约规定；当前做成构造参数，
   默认「首打 + 2 次补打」。若业务要求无限补打，把上限调大或后续加「管理员解锁」。
4. **备注单打印只输出备注自身**：不重复原单明细，避免一张票打两遍；原单仅用于客户快照。
5. **换算只支持计数型单位**：`unitsPerPackage` 是整数，重量 / 面积 / 长度型包装换算契约未定义，
   本模块不猜；需要时先在 `shared` 的 `PackingSpec` 扩展（接口只增不改）。
6. **每次 `render` 都算一次打印**：没有幂等键；S13 若要防网络重试重复打，可在网关层做请求去重
   （打印是物理动作，服务端不做「重复请求返回上次结果」的静默）。
7. **备注单读 Port 是消费侧契约**：S10 落地 `NotePort.get` 后，组合根适配器即可接上；
   S12 不因此改 Port 签名。
