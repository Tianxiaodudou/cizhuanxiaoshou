# 代码地图 · fpk-server/note/src/main/kotlin/com/jiancai/note

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.note`：5 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `NoteException.kt` | 备注单用例失败（S10）。 |
| `NoteMigrations.kt` | S10 的迁移（版本号段 500–599，见 `db-core/module.md`；本模块只占 500）。 |
| `NotePort.kt` | 差异备注单 Port：S10 唯一对外入口（创建、作废）。 |
| `NoteRepository.kt` | 备注单持久化形态（内部类型）：只在 S10 内流转，对外一律转成 [NoteDto]（铁律 2）。 |
| `NoteService.kt` | [NotePort] 的唯一实现：差异备注单用例层。 |
