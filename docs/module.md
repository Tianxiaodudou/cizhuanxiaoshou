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
| `tools/gen-code-map.ps1` | 生成各模块 ``code-map.md``（**全层级目录**，中文）+ 每个 `.kt` 文件头部的行号级代码地图 |
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
| `code-map-freshness` | pre-push | `docs/tools/verify-hooks.ps1 -Check code-map` | warn |

`docs/tools/verify-hooks.ps1` 会读取同一份配置语义并实际执行，因此「hooks 生效」可本地验证
（不需要 dsh 运行时）：`powershell -File docs/tools/verify-hooks.ps1` → `HOOKS OK`。

## 代码地图口径（2026-11-11 追加）

任务要求「所有文件夹层级都要有代码地图、所有源码文件头部都要有本文件的代码地图、中文、精确到行」：

1. **目录级**：`docs/tools/gen-code-map.ps1` 递归遍历 `shared/`、`fpk-server/`、`android-app/` 与仓库根，
   给**每一个目录**生成 `code-map.md`（中文：本目录职责 + 文件清单 + 子目录清单）。
   实测生成 **394 份**目录地图（`docs/` 自身的地图手写，避免与生成器互相覆盖）。
2. **文件级**：同一脚本给每个 `.kt` 文件顶部写「本文件代码地图」注释块，逐条列出
   `第 N 行 / 种类（函数/类/对象/接口）/ 名称`；行号是该符号在文件里的真实起始行。
   实测写入 **236 个**源码文件。
3. **可重跑**：脚本对已有文件头是幂等的（先剥旧头再生成），改代码后重跑即可；
   `verify-hooks.ps1 -Check code-map` 会检查每个源码包目录都有地图（缺一份即失败）。
4. **脚本自身 ASCII**：PowerShell 5.1 会把无 BOM 的 UTF-8 当 ANSI，中文文案集中在
   `tools/code-map-template.json`（`-Encoding UTF8` 读取），脚本里不写中文。

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
