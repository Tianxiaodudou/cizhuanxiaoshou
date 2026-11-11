# C03 · app-shell

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

底栏 3 tab、路由表装配、首页容器、客户端组合根。

写入范围：`android-app/app-shell/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `NavGraph.kt`：导航图装配、tab 分组、启动落地、组合根（`TokenBridge` 打破 C00↔C01 的构造环） |

## 入口（Port）

`NavGraph`、`AppShell`、`ClientAssembly`、`TokenBridge`

## 依赖

C01 / C02 / C04–C12

## 禁止

feature 之间互相 import UI；shell 自己判断 token。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:app-shell:test`：**`NavGraphTest` 9 例 全部通过（0 失败 / 0 跳过）**。

- [x] 路由跳转正常：9 条路由全部装配、底栏恰好 3 tab、启动落地由 C01 决定
- [x] 重复 / 未知路由 id 直接失败，避免静默跳错页
- [x] feature 之间零互相 import（唯一例外：C08 复用 C06 录入壳，卡上明确要求）
- [x] 扫描证明 shell 不处理登录凭证（只把 token 请求转给 C01）

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C03（客户端阶段 4）：应用外壳 + `NavGraphTest` 9 例 | 1.1.0（未改契约） |
