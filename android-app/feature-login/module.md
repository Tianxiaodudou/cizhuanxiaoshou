# C04 · feature-login

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

登录页、服务端地址可改。

写入范围：`android-app/feature-login/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `LoginRoute.kt`：登录屏、地址保存 / 复位、登录动作（只调 C01） |

## 入口（Port）

`LoginRoute`

## 依赖

C00（地址存储）、C01（`SessionGate`）、C02（组件）

## 禁止

自行保存 token；自行解析 401。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:feature-login:test`：**`LoginRouteTest` 5 例 全部通过（0 失败 / 0 跳过）**。

- [x] 登录可用：地址先归一化保存，再调 `SessionGate.login`，成功即落首页
- [x] 服务端地址可改：非法地址就地报 `E422` **且不发请求**，不覆盖已保存地址
- [x] 本模块不接触 `LoginResultDto` / `SessionStore` / 鉴权头（测试扫描）

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C04（客户端阶段 4）：登录页 + `LoginRouteTest` 5 例 | 1.1.0（未改契约） |
