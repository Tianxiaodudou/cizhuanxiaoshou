# C00 · core-network

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

HTTP 客户端、token 注入、错误码统一解析。

写入范围：`android-app/core-network/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `ApiClient.kt`：`HttpMethod` / `ApiRequestSpec` / `ApiResult` / `TokenProvider` / `ApiClient` |
| `HttpApiClient.kt`：拼 URL、注入契约版本 / token / 幂等键、统一解析 `ErrorResponse` |
| `ApiPaths.kt`：客户端唯一的路径 + 序列化器清单（S13 路由一一对应） |
| `HttpTransport.kt`：传输层抽象 + JDK `HttpClient` 实现 |
| `ServerAddress.kt`：服务端地址归一化与存储 |

## 入口（Port）

`ApiClient`（接口 + `HttpApiClient` 实现）、`ApiPaths`（唯一接口清单）、`ServerAddress` / `ServerAddressStore`

## 依赖

无（只依赖 `shared` 契约层）

## 禁止

业务逻辑；金额 / 单号计算；保存 token；自写打印逻辑。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:core-network:test`：**`ApiClientTest` 14 例 全部通过（0 失败 / 0 跳过）**。

- [x] 错误码统一解析：`test` 逐条校验 `ErrorCode` 全枚举；未知码保留原串并走通用文案；错误体不可解析时按 HTTP 状态兜底
- [x] token 自动注入：有 token 注入 `Authorization: Bearer`，未登录不注入；token 只来自 C01 的 `TokenProvider`
- [x] 网络故障归一为可重试的 `E503`，不向界面抛异常
- [x] 写接口自动带 `Idempotency-Key`（取自 Cmd 的 `client_request_id`）

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C00（客户端阶段 4）：网络请求 + `ApiClientTest` 14 例 | 1.1.0（未改契约） |
