# S13 · api-gateway

> contractVersion = **1.1.0**（只消费 `shared` 契约；本模块不新增跨端 DTO，也不建表）

## 职责

对客户端暴露的**唯一 HTTP 入口**：路由、权限拦截、错误码映射、幂等键校验。

- **只转发**：handler 里只有「取参数 → 调 Port → 交 DTO」，没有任何金额 / 数量 / 单号 / 日期计算；
- **只拦截**：契约主版本、请求体大小、token、角色、幂等键，全部在调用业务 Port 之前判定；
- **只映射**：HTTP 状态、错误体、客户端动作全部来自 `shared` 的 `ErrorCode`（铁律 9），
  网关不维护第二张映射表；
- **不建表、不写 SQL**：网关没有任何 repository / 数据库访问（铁律 3 / 10）。

写入范围：`fpk-server/api-gateway/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `ApiGateway.kt` | 核心调度：路由匹配 → 契约/体积/鉴权/幂等 → handler → 统一响应 + 错误兜底 |
| `GatewayRoutes.kt` | 全部路由（S02–S12 一个入口一张路由，见下表） |
| `GatewayDeps.kt` | 依赖聚合：只持有各模块 Port + `TxRunner`（不持有 `SerialPort`，见待确认 1） |
| `Route.kt` | 路由模型 / 权限级别 / `RouteContext`（取参、解码、查询参数）/ 路由表匹配 |
| `AuthGate.kt` | Bearer 解析 + `AuthPort.verify` + 角色判定；`E403` 的唯一抛出点 |
| `Idempotency.kt` | 幂等键校验 + 指纹 + 有界存储（`IdempotencyStore` / `InMemoryIdempotencyStore`） |
| `ErrorMapping.kt` | 异常 → `ErrorCode` → `ErrorResponse` → HTTP 状态（含 5xx 消息脱敏） |
| `Http.kt` | `ApiRequest` / `ApiResponse` / `Payload` / `RouteOutcome`（与框架无关） |
| `HttpServerAdapter.kt` | JDK `com.sun.net.httpserver` 绑定层（唯一框架相关文件） |
| `GatewayException.kt` | 网关自身失败（带 `ErrorCode`） |

测试 5 个文件、共 **69 例**：`PermissionTest`（12）、`IdempotencyTest`（9）、`ErrorMappingTest`（13）、
`RouteTableTest`（10）、`RoutesBehaviourTest`（18）、`HttpAdapterTest`（7）。
`TestFakes.kt` 用内存替身实现全部 Port：网关只做转发，替身才能直接观察调用次数。

## 路由表（对客户端暴露的唯一入口）

| 方法 | 路径 | 权限 | 幂等 | 转发到 |
| --- | --- | --- | --- | --- |
| GET | `/contract` | 公开 | – | `ContractInfoDto`（+ S01 商家名，可选） |
| POST | `/auth/login` | 公开 | – | `AuthPort.login` |
| GET | `/me` | 登录 | – | `AuthPort.verify` 结果回显 |
| GET/POST/PUT/DELETE | `/accounts[/{id}]` | 管理员 | – | `AccountAdminPort` |
| GET | `/customers?phone=` | 登录 | – | `CustomerPort.findByPhone` |
| POST | `/customers` | 登录 | – | `CustomerPort.create` |
| GET | `/categories[/{id}]` | 登录 | – | `CategoryPort.list/get` |
| POST/PUT/DELETE | `/categories[/{id}]` | 管理员 | – | `CategoryPort.create/update/delete` |
| GET | `/prices?customer_id&category_id&batch_tag` | 登录 | – | `PricePort.resolve` |
| POST | `/prices/change` | 管理员 | – | `PricePort.changePrice` + S14 留痕（同事务） |
| GET | `/orders/today` | 登录 | – | `OrderPort.today` |
| GET | `/orders` | 登录 | – | `OrderPort.getRecent`（电话 / 客户 / 类型 / 日期 / 单号 / 分页） |
| GET | `/orders/{orderNo}/receivable` | 登录 | – | `OrderPort.getReceivable` |
| POST | `/orders` | 登录 | **✓** | 退货单先 `ReturnCheckPort.validate` → `OrderPort.create`（同事务） |
| PUT | `/orders/{orderNo}` | 登录 | – | `OrderPort.update` |
| GET | `/orders/{orderNo}/returnable-batches` | 登录 | – | `ReturnCheckPort.availableBatches` |
| POST | `/orders/{orderNo}/return-check` | 登录 | – | `ReturnCheckPort.validate` |
| POST | `/notes` | 登录 | **✓** | `NotePort.create` |
| POST | `/notes/{noteNo}/void` | 登录 | – | `NotePort.void` |
| POST | `/settlements` | 登录 | **✓** | `SettlePort.submit` |
| POST | `/settlements/{settleNo}/audit` | 管理员 | – | `SettlePort.audit` + S14 留痕（同事务） |
| GET | `/customers/{customerId}/balance` | 登录 | – | `SettlePort.getBalance` |
| GET | `/audit` | 管理员 | – | S14 `AuditPort.entries`（审计查询，1.2.0 追加） |
| GET | `/print/{docNo}` | 登录 | – | `PrintPort.render` → `text/plain`（80mm 文本 + `X-Print-*` 头） |

**没有取号接口**：单号只能由业务模块内部经 S07 产出（铁律 8）。客户端拿不到 `SerialPort`。

## 权限口径（验收「权限在 API 层拦截」）

| 场景 | 结果 |
| --- | --- |
| 公开路由（`/contract`、`/auth/login`） | 不解析 token，不校验旧凭证 |
| 缺 `Authorization` / 非 `Bearer` / token 空白 | `E401` 401 |
| token 过期 / 伪造 | 保留 S02 的 `E401` |
| 账号被禁用 | 保留 S02 的 `E6004` |
| 店员访问管理员接口 | `E403` 403，**业务 Port 零调用**（用例直接断言调用计数） |

`E403` 在全仓库只有 `AuthGate.kt` 一处抛出（`RouteTableTest.e403_is_only_raised_by_the_auth_gate`）。

## 错误码口径（验收「错误码统一」）

- 业务异常：读各模块 `*Exception.code`（`ErrorMapping` 里逐个显式列，不用反射）；
- 网关异常：`GatewayException.code`；
- 库层故障（`DbException` / `SQLException`）→ `E503`；序列化失败 → `E400`；`IllegalArgumentException` → `E422`；
- 其它未知异常 → `E500`，**只下发 `ErrorCode.message` 的通用文案**（不透出栈 / SQL）；
- 每个响应都带 `X-Contract-Version` 与 `X-Trace-Id`；错误体里的 `trace_id` 与响应头同值。

## 幂等键口径（验收「幂等键校验」）

- 只有三个「会产生单据」的写接口要求 `Idempotency-Key`：`POST /orders`、`POST /notes`、`POST /settlements`；
- 缺失 / 空白 / 超 128 字符 → `E422`，**在任何业务 Port 之前失败**；
- 同 key + 同指纹（方法 + 路径 + 查询 + 请求体）→ 重放首次响应，附 `X-Idempotent-Replay: true`，
  业务只执行一次（用例用 Port 调用计数证明）；
- 同 key + 不同指纹 → `E409`；
- 只缓存成功响应：失败即释放 key，客户端可用同一个 key 重试；
- 存储实现：`InMemoryIdempotencyStore`（有界 LRU，默认 10000 条，线程安全）。
  多实例部署要换共享存储（换 `IdempotencyStore` 实现即可，不改路由）。

## 跨 Port 事务（铁律 4）

| 场景 | 事务范围 |
| --- | --- |
| 退货单建单 | `ReturnCheckPort.validate` + `OrderPort.create` 同一事务，校验失败不留空号 |
| 管理员改价 | `PricePort.changePrice` + 从 `PricePort.history` 取最新台账 + `AuditPort.priceChanged` 同一事务 |
| 结算审核 | `SettlePort.audit` + `AuditPort.settleAudited` 同一事务 |

事务由 `GatewayDeps.txRunner`（db-core `TxRunner`）包裹，网关自己**不提交、不回滚**。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:api-gateway:test`：**69 个用例全部通过（0 失败 / 0 跳过）**。

- [x] 权限在 API 层拦截：`PermissionTest` 12 例（公开 / 401 / 403 / 管理员 / 店员 / 身份只作为数据字段下传）。
- [x] 错误码统一：`ErrorMappingTest` 13 例（全枚举状态自校验、未知异常脱敏、`E503`、`E413`、契约主版本 `E409`、
      trace 头与错误体一致、各模块异常码保留）。
- [x] 幂等键校验：`IdempotencyTest` 9 例（缺失/空白/超长 422、同请求重放、不同请求 409、失败不烧 key、
      备注单与结算同样受保护、非幂等路由不受影响、存储有界）。
- [x] 只转发 / 不实现业务：`RouteTableTest` 10 例（路由清单与卡一致、幂等只在三处、管理员清单、
      无取号接口、打印只有一个文本入口、源码无 SQL/repository/`Money.ofPlain`/单号前缀/`println`）。
- [x] HTTP 适配：`HttpAdapterTest` 7 例（真起服务、登录往返、401 错误体、查询串、UTF-8 中文往返、
      不支持方法 404、trace 头、停止后重启）。
- [x] 路由行为：`RoutesBehaviourTest` 18 例（S03–S12 每模块入口各一例，含退货先校验、改价/审核留痕、打印文本与次数）。

## 装配（组合根）

```kotlin
val deps = GatewayDeps(
    txRunner = db.txRunner,
    auth = auth, accounts = accounts, customers = customers, categories = categories,
    prices = prices, orders = orders, returns = returns, notes = notes,
    settlements = settlements, printing = print, audit = audit, config = config,
)
val server = HttpServerAdapter(ApiGateway(deps))
server.start(port = 8080, host = "0.0.0.0")
```

## 待确认（不在本模块写入范围内）

1. **`SerialPort` 没有注入网关**：任务卡的依赖清单列了 S07 `SerialPort.next(type)`，但若网关暴露取号接口，
   客户端可以直接消耗单号，违反铁律 8。本模块按「单号只由业务模块内部经 S07 产出」执行，未注入 `SerialPort`。
   如果确实需要「客户端预取号」，应新增一个明确的业务用例（并同步铁律 8），不要在网关私自开放。
2. **`S01 ConfigPort` 是可选的**：卡上 S13 依赖未列 S01，但 `ContractInfoDto.merchantName` 来自 S01。
   本模块做成可选注入（null 时握手的 `merchant_name` 为 null），不阻塞装配。
3. **S14 `AuditPort` 是新增依赖**：卡上 S13 依赖未列 S14，但「改价 / 审核有留痕」必须有人在业务成功后调用
   `AuditPort`。网关是唯一知道「请求已成功」的地方，因此在同一事务里调用；S14 未接入时可为 null。
4. **幂等存储当前是进程内**：单门店单实例够用；多实例需要共享存储（新增 `IdempotencyStore` 实现即可）。
   若要求「重启后仍防重放」，需要一张网关自己的表（新增迁移 + repository），属接口只增不改的追加工作。
5. **`S08 OrderPort` 仍没有 `get(tx, orderNo)`**：网关用 `getRecent(OrderQuery(orderNo = ...))` 精确读单
   （契约 1.1.0 已支持 `OrderQuery.orderNo`）。S08 若追加 `get`，网关只需替换 `requireOrder` 的实现。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 S13：路由表 / AuthGate / ErrorMapping / Idempotency / HTTP 适配 + 69 例单测 | 1.1.0（未改契约） |
| 2026-11-11 | ① 追加 `GET /audit` 管理员只读路由（消费 1.2.0 的 `AuditEntryDto`）；② 幂等存储新增持久化实现 `SqliteIdempotencyStore` + 迁移 800 的 `gateway_idempotency` 表（重启 / 多实例仍可重放，24h TTL）；③ 原单读取改用 S08 新增的 `OrderPort.get`；④ 单测 69 → 78 例。 | 1.2.0 |
