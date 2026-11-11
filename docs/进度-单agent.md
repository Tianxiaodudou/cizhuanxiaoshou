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

## 发版与真机联调（第五轮）

| 项目 | 结果 |
| --- | --- |
| GitHub 推送 | `main` 已推送（含 S09–S14 / C00–C12 / 代码地图 / 发版工程） |
| GitHub Actions `release.yml` | 跑通：version 递增 → server（FPK，内置 JRE）→ client（APK）→ Release 双产物 |
| 发布版本 | v1.0.7（Release） |
| FPK 安装（fnOS 192.168.80.129） | 按 FPK 结构落盘 `/var/apps/jiancai` → `cmd/main start` → `:8080/contract` 返回 1.2.0；管理员 admin 已创建 |
| APK 安装（Redmi K40） | `adb install -r` 成功，包名 `com.jiancai.android.app`，MainActivity 已启动（设备锁屏，界面操作待解锁后补验） |
| 两端连通 | 手机 `curl 127.0.0.1:8080/contract`（adb reverse → PC Node 转发 → fnOS）拿到 1.2.0 契约响应 |

## 代码地图约定（第三轮定稿：总览 + 文件头）

1. **总览地图** `docs/CODEMAPS/`：按领域拆分，`architecture.md`（分层 / 数据流 / 四条硬边界）、
   `backend.md`（路由 ↔ Port ↔ 表 / 迁移段位 / 错误码 / 事务）、`frontend.md`（页面树 / 状态流 / 客户端边界）、
   `conventions.md`（铁律 / 验证 / 已知限制）、`README.md`（字段规范与分工）。
2. **文件头**：每个 `.kt` 顶部六项——`@file` 仓库相对路径、`@role` 一句话职责、`@exports` 对外类型/函数、
   `@depends` 直接依赖的内部模块、`@calledBy` 已知主要调用方（≤3）、`@sideEffects` 数据库/文件/网络副作用。
   不再写行号与逐行摘要（易变、必然过期）。
3. **生成与检查**：`gen-code-map.ps1` 幂等写文件头；`check-file-headers.ps1` 检查前 10 行 `@file`，
   默认只警告，`-Strict` 才失败（已作为 `file-header-check` hook 接入）。
4. 已取消逐目录的 `code-map.md`（与总览重复且易过期）；模块职责仍由各模块 `module.md` 承担。

## 中断续跑要点

1. 本轮写入范围：`fpk-server/return-check|note|settlement|print|api-gateway|observability|app/**`、
   `android-app/**`、`docs/**`，以及**经授权的**「已完成模块」最小改动：
   `shared`（`AuditEntryDto` + `CONTRACT_VERSION` 1.2.0 + 镜像文件 + module.md 变更记录）、
   `fpk-server/order`（`OrderPort.get` + OrderService 实现 + 测试 fake）、
   `fpk-server/db-core/module.md`（段位表补 700/800）。
2. 新增模块必须先在根 `settings.gradle.kts` `include`（已包含全部 30 个模块）。
3. 代码地图与文件头由 `docs/tools/gen-code-map.ps1` 统一生成，不要手改。
