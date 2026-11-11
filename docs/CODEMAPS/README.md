# 代码地图 · 总览（docs/CODEMAPS）

代码地图分两部分，职责不重叠：

| 部分 | 位置 | 回答的问题 | 变更频率 |
| --- | --- | --- | --- |
| **总览地图** | `docs/CODEMAPS/*.md`（本目录） | 系统怎么运转：分层、数据流、路由映射、页面树、边界与约定 | 低（架构 / 接口变化时改） |
| **文件头注释** | 每个 `.kt` 源码文件顶部 | 这个文件在系统中站什么位置 | 随文件走，改动时同改 |

## 文件清单

| 文件 | 内容 |
| --- | --- |
| [architecture.md](architecture.md) | 分层系统图、模块依赖、数据流、事务 / 权限 / 打印 / 单号四条硬边界 |
| [backend.md](backend.md) | S13 路由 ↔ Port ↔ 表的映射、迁移段位、错误码口径、审计与幂等 |
| [frontend.md](frontend.md) | 客户端页面树、3 tab / 5 按钮、状态流（SessionGate）、网络层与打印 deny |
| [conventions.md](conventions.md) | 十条铁律、测试与验证方式、文件头字段规范、已知限制 |

## 文件头字段（各源码文件顶部）

```kotlin
/*
 * @file fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ApiGateway.kt
 * @role 网关核心：请求 → 路由 → 鉴权 → 幂等 → handler → 统一响应。
 * @exports ApiGateway
 * @depends shared
 * @calledBy fpk-server/api-gateway/.../HttpServerAdapter.kt, fpk-server/app/.../GatewayMain.kt
 * @sideEffects 无（纯计算 / 转发）
 */
```

| 字段 | 写什么 | 不写什么 |
| --- | --- | --- |
| `@file` | 仓库相对路径 | 绝对路径 |
| `@role` | 一句话职责 + 领域边界 | 实现细节、逐行说明 |
| `@exports` | 对外（public）类型 / 函数 / 常量名 | 私有、internal 成员 |
| `@depends` | 直接 import 的内部模块 | 标准库、第三方库 |
| `@calledBy` | 已知主要调用方（≤3 个文件） | 全量调用列表 |
| `@sideEffects` | 数据库写 / 读、文件 IO、网络 | 纯日志 |

- 行号、接口签名、调用顺序这类**易变信息不进文件头**——它们在代码里，复制一份必然过期。
- 文件头由 `docs/tools/gen-code-map.ps1` 生成（幂等，先剥旧头再写新头）；改代码后重跑。
- 新增文件必须带 `@file`：`docs/tools/check-file-headers.ps1` 会在前 10 行里找它（默认只警告，`-Strict` 才失败）。
