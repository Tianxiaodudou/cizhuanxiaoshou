# 代码地图 · fpk-server/print/src/main/kotlin/com/jiancai/print

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.print`：11 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `NoteReadPort.kt` | 备注单只读来源（S12 消费侧声明的最小 Port）。 |
| `PrintConversion.kt` | 打印用的装箱换算（只在服务端做，客户端零换算）。 |
| `PrintDocumentDto.kt` | 打印结果（S12 唯一出参 DTO）。 |
| `PrintException.kt` | 打印用例失败（S12）。 |
| `PrintLayout.kt` | 80mm 票据版式（S12 唯一真源，不放到 shared：打印排版属本模块）。 |
| `PrintMigrations.kt` | S12 的迁移（版本号段 600–699，见 `db-core/module.md`）。 |
| `PrintPort.kt` | 打印 Port：S12 唯一对外入口（80mm 渲染 + 打印次数）。 |
| `PrintRenderer.kt` | 票据渲染器：把 S08 / S10 的 DTO 变成 80mm 文本与结构化打印视图。 |
| `PrintRepository.kt` | `print_job` 的唯一 SQL 出口（铁律 10）。 |
| `PrintService.kt` | [PrintPort] 的唯一实现：查单据 → 判打印次数 → 渲染 → 落台账，全部在一个事务里。 |
| `PrintText.kt` | 80mm 热敏纸的等宽文本工具。 |
