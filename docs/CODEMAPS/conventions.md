# 约定与治理（conventions）

## 十条铁律（全程不可违背）

1. 目录即模块，模块只通过 Port 接口通信，接口只增不改。
2. 返回 DTO，不返回 Entity。
3. 禁止跨模块访问 repository / 表；需要别人的数据就调对方的 Port。
4. 跨模块事务由 db-core 的 `TxRunner` 统一包裹，Port 方法接收事务上下文。
5. 金额、单号、批号类型一律服务端产出，客户端只展示、零计算。
6. 打印只在服务端（S12），客户端全局 deny 打印相关关键词。
7. 权限统一在 api-gateway（S13）拦截，业务模块不各自判断权限。
8. 单号仅 S08 通过 `SerialPort` 获取，其它模块不得自行生成单号。
9. 共享定义只放 `/shared`（两端唯一真源），禁止两端各写一套字符串。
10. SQL 只能出现在各模块自己的 repository 内，表归属各模块。

## 代码地图约定

| 部分 | 位置 | 维护方式 |
| --- | --- | --- |
| 总览 | `docs/CODEMAPS/{architecture,backend,frontend,conventions}.md` | 手写；架构 / 路由 / 页面变化时更新 |
| 文件头 | 每个 `.kt` 顶部 `@file/@role/@exports/@depends/@calledBy/@sideEffects` | `docs/tools/gen-code-map.ps1` 生成，改代码后重跑 |

- **总览不逐文件列表**，**文件头不画架构图**；两边都只保留低变更频率的信息。
- 行号、接口签名、调用顺序不写进文件头（易变，复制必过期）。
- 新增文件必须带 `@file`；`docs/tools/check-file-headers.ps1` 在前 10 行内找不到就警告，`-Strict` 时失败。

## 验证方式

| 命令 | 覆盖 |
| --- | --- |
| `./gradlew build --offline` | 30 个模块编译 + 596 个单测 |
| `powershell -File docs/tools/scan-buttons.ps1` | 客户端打印关键词（当前 0 违规） |
| `powershell -File docs/tools/check-file-headers.ps1` | 文件头 `@file` 完整性（警告） |
| `powershell -File docs/tools/verify-hooks.ps1` | hooks 自检（扫描 + 文件头 + 模块文档） |

## hooks（docs/hooks/dsh-hooks.json）

| hook | 触发 | 命令 | 失败策略 |
| --- | --- | --- | --- |
| `print-keyword-deny` | pre-commit | `scan-buttons.ps1` | block |
| `button-scan` | pre-push | `scan-buttons.ps1` | warn |
| `file-header-check` | pre-push | `check-file-headers.ps1` | warn |

## 契约版本

- 唯一真源 `shared/Contract.kt` 的 `CONTRACT_VERSION`；镜像 `shared/contract.version` 由测试强制一致。
- 只追加 → 次版本 +1；删改 / 语义变化 → 主版本 +1。当前 **1.2.0**（1.2.0 加入了 `AuditEntryDto`
  与 `OrderPort.get` 的只读查询）。

## 已知限制

1. 本机无 Android SDK / AGP：C00–C12 以 JVM 模块 + JVM 单测验证，没有 APK；真机联调用手机 curl 打真实网关。
2. 组合根 `GatewayMain` 里 `PrintService` 的 `NoteReadPort` 传 null：备注单打印暂返回 `E7001`，
   等 S10 追加只读 `get(noteNo)` 后接上（`print/module.md` 已记录）。
3. 审计只覆盖「改价 / 审核」两类动作；追加动作需要新迁移（不能改 700）。
