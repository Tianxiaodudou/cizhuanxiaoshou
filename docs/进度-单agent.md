# 进度 · 单 agent 剩余模块

> 一行一个模块；「测试结果」为本机真实执行 `./gradlew :<模块>:test` 的用例数（JDK 17 + Gradle 8.10.2，--offline）。
> 第二轮的「待确认项」已全部执行（shared 1.2.0、S08 `OrderPort.get`、db-core 段位表、S13 持久化幂等 + 审计路由、代码地图全层级）。
> 真机联调：Redmi K40（Android 11，`192.168.1.32`）经 `adb reverse` 直连本机网关，全链路通过（见 `features.md`）。

| # | 模块 | 完成时间 | 测试结果 | 遗留 |
| --- | --- | --- | --- | --- |
| 1 | S09 return-check | 2026-11-11 14:40 | 49 例通过 / 0 失败 | 代码地图与文件头已补齐 |
| 2 | S10 note | 2026-11-11 14:40 | 29 例通过 / 0 失败 | 原单查找改用 `OrderPort.get`（1.2.0），不再翻页 |
| 3 | S11 settlement | 2026-11-11 14:40 | 37 例通过 / 0 失败 | 审核留痕由 S13 调 S14（同一事务） |
| 4 | S12 print | 2026-11-11 14:40 | 25 例通过 / 0 失败 | 客户端 deny 由 SCAN + C12 守卫 |
| 5 | S13 api-gateway | 2026-11-11 14:40 | 78 例通过 / 0 失败 | 幂等台账持久化（迁移 800，24h TTL）；`GET /audit` 管理员只读 |
| 6 | S14 observability | 2026-11-11 14:40 | 33 例通过 / 0 失败 | `entries()` 输出 `AuditEntryDto`（1.2.0） |
| 7 | C00 core-network | 2026-11-11 14:40 | 14 例通过 / 0 失败 | 真实 Android 工程换 OkHttp 只需实现 `HttpTransport` |
| 8 | C01 core-session | 2026-11-11 14:40 | 9 例通过 / 0 失败 | 持久化 `SessionStore`（DataStore）留给真实工程 |
| 9 | C02 core-ui | 2026-11-11 14:40 | 8 例通过 / 0 失败 | Compose 实现由真实工程补，JVM 侧锁规格 |
| 10 | C03 app-shell | 2026-11-11 14:40 | 9 例通过 / 0 失败 | 底部导航 UI 待 Compose 实现 |
| 11 | C04 feature-login | 2026-11-11 14:40 | 5 例通过 / 0 失败 | — |
| 12 | C05 feature-home | 2026-11-11 14:40 | 6 例通过 / 0 失败 | — |
| 13 | C06 feature-order-entry | 2026-11-11 14:40 | 8 例通过 / 0 失败 | 4 类录入页共用同一 `ScreenSpec` |
| 14 | C07 feature-query | 2026-11-11 14:40 | 5 例通过 / 0 失败 | 分页按 page 递增 |
| 15 | C08 feature-today | 2026-11-11 14:40 | 6 例通过 / 0 失败 | 跨日锁依赖服务端 `E2003` |
| 16 | C09 feature-note | 2026-11-11 14:40 | 6 例通过 / 0 失败 | — |
| 17 | C10 feature-settle | 2026-11-11 14:40 | 5 例通过 / 0 失败 | — |
| 18 | C11 feature-admin | 2026-11-11 14:40 | 6 例通过 / 0 失败 | 权限完全依赖服务端 `E403` |
| 19 | C12 feature-settings | 2026-11-11 14:40 | 6 例通过 / 0 失败 | 只读打印状态，无打印入口 |
| 20 | DOCS 说明文档 | 2026-11-11 14:45 | 文档核对通过 | `routes.md` / `features.md` / 本表 / 各模块 `module.md` |
| 21 | SCAN 按钮扫描 | 2026-11-11 14:45 | 0 违规；注入违禁词退出码 1 | 关键词真源取自 C02 `ClientDenyPolicy` |
| 22 | HOOKS 自动化 | 2026-11-11 14:45 | `verify-hooks.ps1` → `HOOKS OK` | 另含代码地图完整性检查 |

## 全量验证（第二轮）

| 命令 | 结果 |
| --- | --- |
| `./gradlew build --offline` | BUILD SUCCESSFUL（30 个 Gradle 模块，596 个单测 0 失败） |
| `powershell -File docs/tools/scan-buttons.ps1` | 0 违规（17 个客户端生产文件，20 个 ButtonSpec / 23 个 @UiButton） |
| `powershell -File docs/tools/verify-hooks.ps1` | HOOKS OK（扫描 + 394 份代码地图齐全） |
| 真机联调 `docs/tools/device-e2e.sh` | Redmi K40 / Android 11：契约、登录、建档、建单、幂等重放、结算审核、备注单、审计、80mm 票据、401 全部通过 |

## 代码地图约定（第二轮新增）

1. **目录级**：仓库里每个目录都有 `code-map.md`（中文，递归到所有层级，文件名 / 子目录 / 职责）。
   生成：`powershell -File docs/tools/gen-code-map.ps1`（可重复执行，内容覆盖写）。
2. **文件级**：每个 `.kt` 源码文件顶部有「本文件代码地图」注释块，列出
   `第 N 行 / 种类 / 名称`，行号是该符号在**当前文件里的真实起始行**。
3. **改了代码就要重跑生成器**，否则行号会漂移；`verify-hooks.ps1 -Check code-map` 可查目录地图是否齐全。
4. 生成器本体保持 ASCII（PowerShell 5.1 会把无 BOM 的 UTF-8 当 ANSI），中文文案全在
   `docs/tools/code-map-template.json`。

## 中断续跑要点

1. 本轮写入范围：`fpk-server/return-check|note|settlement|print|api-gateway|observability|app/**`、
   `android-app/**`、`docs/**`，以及**经授权的**「已完成模块」最小改动：
   `shared`（`AuditEntryDto` + `CONTRACT_VERSION` 1.2.0 + 镜像文件 + module.md 变更记录）、
   `fpk-server/order`（`OrderPort.get` + OrderService 实现 + 测试 fake）、
   `fpk-server/db-core/module.md`（段位表补 700/800）。
2. 新增模块必须先在根 `settings.gradle.kts` `include`（已包含全部 30 个模块）。
3. 代码地图与文件头由 `docs/tools/gen-code-map.ps1` 统一生成，不要手改。
