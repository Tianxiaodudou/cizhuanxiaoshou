# DOCS · 说明文档 / SCAN · 按钮扫描 / HOOKS · 自动化

> contractVersion = **1.1.0**（文档层不改契约，只映射实现）

## 职责

阶段 5 的三个收尾模块合并落在 `docs/` 下：

- **DOCS**：`routes.md`（路由表）、`features.md`（功能清单）、`进度-单agent.md`（断点续跑记录），
  以及各模块的 `module.md` / `code-map.md` 索引；
- **SCAN**：`tools/scan-buttons.ps1`——只读扫描客户端里是否出现打印相关按钮 / 文案；
- **HOOKS**：`hooks/dsh-hooks.json` + `tools/verify-hooks.ps1`——把扫描与代码地图新鲜度变成
  提交前的自动门禁。

写入范围：`docs/**`（**只读其他模块**：扫描脚本不修改任何业务代码）。

## 目录

| 路径 | 内容 |
| --- | --- |
| `routes.md` | 服务端 S13 路由 + 客户端 13 个模块的入口对照 |
| `features.md` | 22 个模块（S09–S14、C00–C12）职责与验收要点 |
| `进度-单agent.md` | 每模块一行的断点续跑记录（完成时间 / 测试结果 / 遗留） |
| `module.md`、`code-map.md` | 本模块（docs）的职责与目录地图 |
| `tools/gen-code-map.ps1` | 给每个 `.kt` 写文件头代码地图（`@file/@role/@exports/@depends/@calledBy/@sideEffects`） |
| `tools/check-file-headers.ps1` | 检查文件头 `@file`（前 10 行，默认只警告，`-Strict` 失败） |
| `tools/scan-buttons.ps1` | 按钮 / 打印关键词扫描（关键词真源来自 C02 `ClientDenyPolicy`） |
| `tools/verify-hooks.ps1` | hooks 自检：跑扫描 + 校验代码地图齐全 |
| `tools/device-e2e.sh` | 真机联调脚本（手机 shell 里 curl 打真实网关，配合 `adb reverse`） |
| `tools/code-map-template.json` | 代码地图的中文文案模板（脚本本体保持 ASCII，兼容 PowerShell 5.1） |
| `hooks/dsh-hooks.json` | dsh hooks 配置（pre-commit 阻断打印关键词，pre-push 校验文档新鲜度） |

## 扫描口径（验收「能扫出违规关键词」）

1. 关键词**不在脚本里重复写**：运行时从
   `android-app/core-ui/src/main/kotlin/com/jiancai/android/ui/UiKit.kt` 的
   `ClientDenyPolicy.FORBIDDEN_KEYWORDS` 解析（铁律 9：共享定义唯一真源）；
2. 例外只有 `ALLOWED_READONLY_LABELS`（「打印状态 / 已打印次数 / 剩余次数」这类只读展示）；
3. 命中任何其它关键词 → 退出码 1，并打印 `文件:行号 命中词 原文`；
4. 脚本同时统计 `ButtonSpec(` 与 `@UiButton(` 的出现次数，作为「按钮清单」快照。

实测（本机执行 `docs/tools/scan-buttons.ps1`）：扫描 13 个客户端模块的生产源码，
**0 处违规**；故意在客户端加一行「打印小票」会被扫出并阻断（退出码 1）。

## hooks 口径（验收「hooks 生效」）

| hook | 触发 | 命令 | 失败策略 |
| --- | --- | --- | --- |
| `print-keyword-deny` | pre-commit | `docs/tools/scan-buttons.ps1` | block（退出码 1） |
| `button-scan` | pre-push | `docs/tools/scan-buttons.ps1` | warn（只报告） |
| `file-header-check` | pre-push | `docs/tools/check-file-headers.ps1` | warn（只警告，不卡构建） |

`docs/tools/verify-hooks.ps1` 会读取同一份配置语义并实际执行，因此「hooks 生效」可本地验证
（不需要 dsh 运行时）：`powershell -File docs/tools/verify-hooks.ps1` → `HOOKS OK`。

## 代码地图口径（2026-11-11 定稿）

代码地图分**两部分**，分工明确：

1. **总览地图** `docs/CODEMAPS/`（按领域拆分，不写巨型文件）：
   [README](../CODEMAPS/README.md)（约定与字段规范）、[architecture](../CODEMAPS/architecture.md)（分层 / 数据流 / 四条硬边界）、
   [backend](../CODEMAPS/backend.md)（路由 ↔ Port ↔ 表、迁移段位、错误码、事务）、
   [frontend](../CODEMAPS/frontend.md)（页面树 / 状态流 / 客户端硬边界）、
   [conventions](../CODEMAPS/conventions.md)（铁律、验证方式、已知限制）。
2. **文件头注释**：每个 `.kt` 顶部写
   `@file / @role / @exports / @depends / @calledBy / @sideEffects` 六项（中文职责，只放低变更频率信息）。
   行号、接口签名、逐行摘要**不写**——它们在代码里，复制必然过期。

维护规则：
- `docs/tools/gen-code-map.ps1` 生成文件头（幂等，先剥旧头再写新头）；改代码后重跑；
- `docs/tools/check-file-headers.ps1` 检查前 10 行是否有 `@file`：默认只警告（不卡构建），`-Strict` 才失败；
- 逐目录的 `code-map.md` 已取消（与总览重复且易过期），模块职责仍由各模块 `module.md` 承担；
- 脚本本体保持 ASCII：PowerShell 5.1 会把无 BOM 的 UTF-8 当 ANSI，中文文案集中在
  `tools/code-map-template.json`（`-Encoding UTF8` 读取）。

## 硬边界

- **只读**：扫描只 `Get-Content`，不写业务代码；生成器只写 `code-map.md`；
- **不改模块边界**：hooks 只调用 `docs/tools/**` 里的脚本，不 import / 改写任何模块；
- **不与实现脱节**：路由表与 `GatewayRoutes` 一一对应（S13 单测 `RouteTableTest` 已锁死清单），
  代码地图由脚本从源码 KDoc 生成。

## 验收对照

- [x] 文档与代码实现一致：`routes.md` 与 `RouteTableTest` 的路由清单一致；`features.md` 覆盖 22 个模块。
- [x] 能扫出违规关键词：`scan-buttons.ps1` 在本仓库 0 违规；注入违禁词立即退出 1（见上「扫描口径」实测）。
- [x] hooks 生效：`verify-hooks.ps1` 跑通两条 hook 并返回 `HOOKS OK`；配置见 `hooks/dsh-hooks.json`。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建阶段 5：路由表 / 功能清单 / 进度表 / 代码地图生成器 / 按钮扫描 / hooks 配置 | 1.1.0（未改契约） |
