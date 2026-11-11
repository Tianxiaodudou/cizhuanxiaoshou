# 服务端总览（backend）

服务端是「唯一对客户端暴露的 HTTP 入口（S13）+ 11 个业务模块 + 1 个审计模块」。
本文件回答：路由打到哪个 Port、哪张表、哪条迁移、什么错误码。

## 路由 ↔ Port ↔ 表

| 方法 | 路径 | Port | 主要表 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `/contract` | `ConfigPort.getConfig` | `config` | 公开；回显契约版本 / 服务端时间 / 商家名 |
| POST | `/auth/login` | `AuthPort.login` | `auth_account` | 公开；成功下发 token + `contract_version` |
| GET | `/me` | `AuthPort.verify` | `auth_account` | 登录 |
| GET/POST/PUT/DELETE | `/accounts[/{id}]` | `AccountAdminPort` | `auth_account`（S02 独占） | 管理员 |
| GET | `/customers?phone=` | `CustomerPort.findByPhone` | `customer` | 登录 |
| POST | `/customers` | `CustomerPort.create` | `customer` | 登录 |
| GET | `/categories[/{id}]` | `CategoryPort.list/get` | `category` | 登录 |
| POST/PUT/DELETE | `/categories[/{id}]` | `CategoryPort.*` | `category` | 管理员 |
| GET | `/prices` | `PricePort.resolve` | `price` | 维度 = 客户 + 型号 + 批号 |
| POST | `/prices/change` | `PricePort.changePrice` | `price`, `price_change_log` | 管理员；同事务写 `audit_log` |
| GET | `/orders/today` | `OrderPort.today` | `order_header`, `order_line` | 服务端日期 |
| GET | `/orders` | `OrderPort.getRecent` | 同上 | 电话 / 客户 / 类型 / 日期 / 单号 / 分页 |
| GET | `/orders/{no}/receivable` | `OrderPort.getReceivable` | `order_header` | 服务端派生金额 |
| POST | `/orders` | S09 `validate` → `OrderPort.create` | `order_header`, `order_line` | 幂等；退货单先校验（同事务） |
| PUT | `/orders/{no}` | `OrderPort.update` | 同上 | 乐观锁 `version`；跨日 `E2003` |
| GET | `/orders/{no}/returnable-batches` | `ReturnCheckPort.availableBatches` | 只读订单 | 只经 Port 读 |
| POST | `/orders/{no}/return-check` | `ReturnCheckPort.validate` | 只读订单 | 校验通过才允许建退货单 |
| POST | `/notes` | `NotePort.create` | `diff_note` | 幂等；原单只读关联 |
| POST | `/notes/{no}/void` | `NotePort.void` | `diff_note` | 只作废不删除 |
| POST | `/settlements` | `SettlePort.submit` | `settlement` | 幂等；应收从 S08 重新读 |
| POST | `/settlements/{no}/audit` | `SettlePort.audit` | `settlement` | 管理员；同事务写 `audit_log` |
| GET | `/customers/{id}/balance` | `SettlePort.getBalance` | `settlement` + S08 应收 | 已收 / 未收读模型 |
| GET | `/audit` | `AuditPort.entries` | `audit_log` | 管理员；`action` / `target_no` / `operator_id` / `limit` |
| GET | `/print/{docNo}` | `PrintPort.render` | `print_job` | 返回 `text/plain` 80mm 文本 + `X-Print-*` 头 |

## 表归属（一张表只有一个主人）

| 模块 | 表 |
| --- | --- |
| S00 db-core | `schema_migration` |
| S01 config | `config` |
| S02 auth | `auth_account` |
| S04 customer | `customer` |
| S05 category | `category` |
| S06 pricing | `price`, `price_change_log` |
| S07 serial | `serial_counter` |
| S08 order | `order_header`, `order_line` |
| S09 return-check | 无表（纯校验） |
| S10 note | `diff_note` |
| S11 settlement | `settlement` |
| S12 print | `print_job` |
| S13 api-gateway | `gateway_idempotency` |
| S14 observability | `audit_log` |

## 迁移段位

| 段位 | 归属 |
| --- | --- |
| 1–99 | db-core |
| 100–199 | S07 serial（100） |
| 200–299 | S01 config（200）、S02 auth（210） |
| 300–399 | S04（300）、S05（310）、S06（320） |
| 400–499 | S08（400/401）、S09 预留 410–419 |
| 500–599 | S10（500）、S11（510） |
| 600–699 | S12（600） |
| 700–799 | S14（700 审计台账） |
| 800–899 | S13（800 幂等台账） |

## 错误码与响应

- 唯一真源：`shared/errors/ErrorCode`（含 HTTP 状态、分类、是否可重试）；S13 只做映射，不写第二张表。
- 响应头：`X-Contract-Version`、`X-Trace-Id`；错误体统一 `ErrorResponse{code,message,details,trace_id}`。
- 5xx 只下发通用文案（不透出栈 / SQL）；业务码保留服务端消息与字段明细。
- 幂等：三个写接口要求 `Idempotency-Key`；同 key 同请求重放首次响应（`X-Idempotent-Replay: true`），
  同 key 不同请求 → `E409`；持久化在 `gateway_idempotency`（24h TTL），重启仍生效。

## 事务边界

| 场景 | 事务范围 |
| --- | --- |
| 建退货单 | `ReturnCheckPort.validate` + `OrderPort.create` |
| 管理员改价 | `PricePort.changePrice` + `PricePort.history` + `AuditPort.priceChanged` |
| 结算审核 | `SettlePort.audit` + `AuditPort.settleAudited` |
| 备注单 | `SerialPort.next(NOTE)` + `NotePort.create`（同一事务） |

## 验证入口

```bash
./gradlew build --offline                        # 30 个模块 / 596 个单测
./gradlew :fpk-server:api-gateway:test            # 78 例：路由 / 权限 / 错误码 / 幂等
JIANCAI_DB_PATH=... JIANCAI_AUTH_SECRET=... ./gradlew :fpk-server:app:runGateway
```
