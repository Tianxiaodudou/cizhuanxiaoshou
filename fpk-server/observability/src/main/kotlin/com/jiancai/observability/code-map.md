# 代码地图 · fpk-server/observability/src/main/kotlin/com/jiancai/observability

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.observability`：7 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `AuditAction.kt` | 审计动作（S14 内部枚举，不跨端；线格式由 `shared` 的 DTO 决定，若将来需要审计查询页再入契约）。 |
| `AuditException.kt` | 审计用例失败（S14）。 |
| `AuditModels.kt` | 一条待留痕的审计事件（S14 内部入参，不跨端）。 |
| `AuditPort.kt` | 审计 Port：S14 唯一对外入口（留痕写入 + 只读查询）。 |
| `AuditRepository.kt` | `audit_log` 的唯一 SQL 出口（铁律 10）。 |
| `AuditService.kt` | [AuditPort] 的唯一实现：校验 → 组装留痕 → 追加一行 → 返回 DTO。 |
| `ObservabilityMigrations.kt` | S14 的迁移（版本号段 700–799；100 S07、200 S01、210 S02、300 S04、310 S05、320 S06、 |
