# 路由表

> 唯一真源：服务端 = `fpk-server/api-gateway/.../GatewayRoutes.kt`（清单由 `RouteTableTest` 锁死）；
> 客户端 = `android-app/core-network/.../ApiPaths.kt`（路径常量与序列化器）；导航 id = `android-app/core-ui/.../AppRoutes`。
> 本文件与实现一一对应，改路由必须同时改这三处 + 本文件。

## 一、服务端 HTTP 路由（S13 api-gateway，对客户端暴露的唯一入口）

| 方法 | 路径 | 权限 | 幂等 | 转发到 |
| --- | --- | --- | --- | --- |
| GET | `/contract` | 公开 | – | 契约握手（版本 / 服务端时间 / 商家名） |
| POST | `/auth/login` | 公开 | – | S02 `AuthPort.login` |
| GET | `/me` | 登录 | – | S02 `AuthPort.verify` 回显 |
| GET | `/accounts` | 管理员 | – | S03 `AccountAdminPort.list` |
| GET | `/accounts/{id}` | 管理员 | – | S03 `get` |
| POST | `/accounts` | 管理员 | – | S03 `create` |
| PUT | `/accounts/{id}` | 管理员 | – | S03 `update` |
| DELETE | `/accounts/{id}` | 管理员 | – | S03 `delete` |
| GET | `/customers?phone=` | 登录 | – | S04 `findByPhone` |
| POST | `/customers` | 登录 | – | S04 `create` |
| GET | `/categories` | 登录 | – | S05 `list` |
| GET | `/categories/{id}` | 登录 | – | S05 `get` |
| POST | `/categories` | 管理员 | – | S05 `create` |
| PUT | `/categories/{id}` | 管理员 | – | S05 `update` |
| DELETE | `/categories/{id}` | 管理员 | – | S05 `delete` |
| GET | `/prices?customer_id&category_id&batch_tag` | 登录 | – | S06 `resolve` |
| POST | `/prices/change` | 管理员 | – | S06 `changePrice` + S14 留痕（同事务） |
| GET | `/orders/today` | 登录 | – | S08 `today` |
| GET | `/orders` | 登录 | – | S08 `getRecent`（电话 / 客户 / 类型 / 日期 / 单号 / 分页） |
| GET | `/orders/{orderNo}/receivable` | 登录 | – | S08 `getReceivable` |
| POST | `/orders` | 登录 | ✓ | 退货先 S09 `validate` → S08 `create`（同事务） |
| PUT | `/orders/{orderNo}` | 登录 | – | S08 `update` |
| GET | `/orders/{orderNo}/returnable-batches` | 登录 | – | S09 `availableBatches` |
| POST | `/orders/{orderNo}/return-check` | 登录 | – | S09 `validate` |
| POST | `/notes` | 登录 | ✓ | S10 `create` |
| POST | `/notes/{noteNo}/void` | 登录 | – | S10 `void` |
| POST | `/settlements` | 登录 | ✓ | S11 `submit` |
| POST | `/settlements/{settleNo}/audit` | 管理员 | – | S11 `audit` + S14 留痕（同事务） |
| GET | `/customers/{customerId}/balance` | 登录 | – | S11 `getBalance` |
| GET | `/audit` | 管理员 | – | S14 `AuditPort.entries`（`action` / `target_no` / `operator_id` / `limit` 过滤） |
| GET | `/print/{docNo}` | 登录 | – | S12 `render`（返回 `text/plain` 的 80mm 文本） |

- **没有取号接口**：单号只由业务模块内部经 S07 产出（铁律 8）。
- 所有响应带 `X-Contract-Version` 与 `X-Trace-Id`；错误体统一为 `shared` 的 `ErrorResponse`。
- 幂等键：写类接口必须带 `Idempotency-Key`（客户端直接用 Cmd 的 `client_request_id`）。

## 二、客户端入口（阶段 4，路径见 C00 `ApiPaths` / 导航 id 见 C02 `AppRoutes`）

| 模块 | 目录 | 路由 / 入口 | 依赖 |
| --- | --- | --- | --- |
| C00 · core-network | `android-app/core-network` | `ApiClient` / `ApiPaths` / `ServerAddress` | `shared` |
| C01 · core-session | `android-app/core-session` | `SessionGate`（唯一 token 判断点） | C00 |
| C02 · core-ui | `android-app/core-ui` | 组件库 / `@UiButton` / `AppRoutes` / `ClientDenyPolicy` | – |
| C03 · app-shell | `android-app/app-shell` | `NavGraph`（底栏 3 tab）/ `AppShell` / `ClientAssembly` | C01、C02、C04–C12 |
| C04 · feature-login | `android-app/feature-login` | `LoginRoute`（`/login`，服务端地址可改） | C00、C01、C02 |
| C05 · feature-home | `android-app/feature-home` | `HomeRoute`（`/home`，5 按钮） | C02 |
| C06 · feature-order-entry | `android-app/feature-order-entry` | `OrderEntryRoute(orderType)`（`/orders/new?type=`） | C00、C02 |
| C07 · feature-query | `android-app/feature-query` | `QueryRoute`（`/query`） | C00、C02 |
| C08 · feature-today | `android-app/feature-today` | `TodayRoute`（`/today`，复用 C06 录入壳） | C00、C02、C06 |
| C09 · feature-note | `android-app/feature-note` | `NoteRoute`（`/note`）+ `SelectOrderPage` | C00、C02 |
| C10 · feature-settle | `android-app/feature-settle` | `SettleRoute`（`/settle`） | C00、C02 |
| C11 · feature-admin | `android-app/feature-admin` | `AdminRoute`（`/admin`，4 子页） | C00、C02 |
| C12 · feature-settings | `android-app/feature-settings` | `SettingsRoute`（`/settings`，只读打印状态） | C00、C02 |

底栏 3 tab：`首页`（`/home`）、`今日`（`/today`）、`设置`（`/settings`）；其余入口从首页 5 个按钮进入。
