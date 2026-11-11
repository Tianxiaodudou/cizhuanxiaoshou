# C01 · core-session

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

登录态、导航守卫（**唯一 token 判断点**）。

写入范围：`android-app/core-session/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `SessionGate.kt`：登录 / 登出 / 过期判定 / 401 收敛 / 启动落地页 |

## 入口（Port）

`SessionGate`、`SessionStore`、`SessionState`、`LaunchDestination`

## 依赖

C00（`ApiClient`、`TokenProvider`）

## 禁止

其它模块判断 token；业务模块解析 401。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:core-session:test`：**`SessionGateTest` 9 例 全部通过（0 失败 / 0 跳过）**。

- [x] token 判断仅此一处：`SessionGateTest` 扫描全部客户端源码，除 C00（仅持有 `TokenProvider` 接口）与 C01 外出现 token / Authorization / expiresAt 即失败
- [x] 登录成功才落会话；过期会话自动清除并回登录页
- [x] 任意业务调用 401 都经 `SessionGate.onFailure` 统一清除会话

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C01（客户端阶段 4）：登录状态 + `SessionGateTest` 9 例 | 1.1.0（未改契约） |
