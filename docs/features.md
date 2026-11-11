# 功能清单与完成状态

> 状态口径：**代码 + 单测 + `module.md` + 代码地图 + `gradlew :模块:test` 绿** 才算完成。
> 本机无 Android SDK：C00–C12 按任务卡允许的方式退化为 **JVM 模块 + JVM 单测**（真实工程换 `android` library 插件，源码不动）。
> 已完成模块（SHARED、S00–S08）只读，本次未改动。

## 阶段 1 · 契约与地基（沿用，未改动）

| 模块 | 职责 | 状态 |
| --- | --- | --- |
| SHARED | 两端唯一真源：DTO / 枚举 / 错误码 / 单号前缀 / 单位，contractVersion = 1.1.0 | 既有 |
| S00 db-core | 唯一 SQLite 连接、`TxRunner`、迁移 | 既有 |
| S07 serial | 单号生成（前缀 + 年 + 月日 + 全局序号） | 既有 |
| S01 config | 部署级商家名称 | 既有 |
| S02 auth | 登录、token 签发与校验、账号密码 | 既有 |
| S03 account | 账号 CRUD（仅管理员） | 既有 |

## 阶段 2 · 业务主链（沿用，未改动）

| 模块 | 职责 | 状态 |
| --- | --- | --- |
| S04 customer | 客户建档 / 按电话查询 / 地址快照 | 既有 |
| S05 category | 型号库 CRUD / 批号类型 | 既有 |
| S06 pricing | 恒定价格、当日可改、跨日锁、改价留痕 | 既有 |
| S08 order | 订单抬头/明细、幂等、乐观锁、改当日单、应收派生 | 既有 |

## 阶段 3 · 周边功能与网关

| 模块 | 职责 | 验收要点 | 状态 |
| --- | --- | --- | --- |
| S09 return-check | 可退数量计算、批次校验（规则独立） | 只经 `OrderPort` 读；规则可独立升级 | ✅ 49 例 |
| S10 note | 差异备注单（关联原单、不改原单、作废） | 原单不被改动；作废用例通过 | ✅ 29 例 |
| S11 settlement | 结算记录、当场判定、审核、已收/未收读模型 | 审核流通过；已收金额不被改动 | ✅ 37 例 |
| S12 print | 打印渲染（80mm、五列/双行、换算、打印次数） | 80mm 渲染正确；次数记录；客户端 deny 生效 | ✅ 25 例 |
| S13 api-gateway | 路由、权限拦截、错误码映射、幂等键校验 | 权限在 API 层拦截；错误码统一；幂等键校验 | ✅ 78 例 |
| S14 observability | 日志、审计留痕（改价、审核） | 改价 / 审核有留痕 | ✅ 33 例 |

## 阶段 4 · 手机端（JVM 降级验证）

| 模块 | 职责 | 验收要点 | 状态 |
| --- | --- | --- | --- |
| C00 core-network | HTTP 客户端、token 注入、错误码统一解析 | 错误码统一解析；token 自动注入 | ✅ 14 例 |
| C01 core-session | 登录态、导航守卫（唯一 token 判断点） | token 判断仅此一处 | ✅ 9 例 |
| C02 core-ui | 通用组件、主题、`@UiButton` 注解 | 组件可复用 | ✅ 8 例 |
| C03 app-shell | 底栏 3 tab、路由表、首页容器 | 路由跳转正常 | ✅ 9 例 |
| C04 feature-login | 登录页、服务端地址可改 | 登录可用；服务端地址可改 | ✅ 5 例 |
| C05 feature-home | 5 按钮布局 | 按钮跳转正常 | ✅ 6 例 |
| C06 feature-order-entry | 4 类录入页共用 `OrderFormComponent` | 4 类共用组件；提交走服务端 | ✅ 8 例 |
| C07 feature-query | 按电话查历史，只调聚合接口 | 只调聚合接口 | ✅ 5 例 |
| C08 feature-today | 改当日单列表 + 编辑（复用录入壳） | 复用录入壳；跨日不可改 | ✅ 6 例 |
| C09 feature-note | 备注单 + 选原单（`SelectOrderPage`） | 备注单正确关联原单 | ✅ 6 例 |
| C10 feature-settle | 结算上传，成功/失败当场回显 | 上传与回显正确 | ✅ 5 例 |
| C11 feature-admin | 型号库、价格、结算审核、账号四子页 | 4 子页可用；权限由服务端把关 | ✅ 6 例 |
| C12 feature-settings | 客户端配置 + 只读打印状态 | 打印状态只读 | ✅ 6 例 |

## 阶段 5 · 收尾与治理

| 模块 | 职责 | 验收要点 | 状态 |
| --- | --- | --- | --- |
| DOCS | `routes.md`、`features.md`、`进度-单agent.md` | 文档与实现一致 | ✅ |
| SCAN | 按钮 / 打印关键词扫描脚本 | 能扫出违规关键词（注入违禁词即退出 1） | ✅ |
| HOOKS | dsh hooks 配置 + 自检脚本 | hooks 生效（`verify-hooks.ps1` 返回 `HOOKS OK`） | ✅ |
| 代码地图 | 总览 `docs/CODEMAPS/`（architecture / backend / frontend / conventions）+ 每个源码文件头 `@file/@role/@exports/@depends/@calledBy/@sideEffects` | 总览与实现一致；`check-file-headers.ps1` 覆盖 236 个 `.kt`（缺 `@file` 仅警告） | ✅ |

## 真机联调（2026-11-11，Redmi K40 / Android 11 / `192.168.1.32`）

`adb reverse tcp:8080 tcp:8080` + `docs/tools/device-e2e.sh`，手机 curl 直接打本机常驻网关
（`:fpk-server:app:runGateway`，SQLite 文件库，迁移到 800）：

| 环节 | 真机结果 |
| --- | --- |
| 契约握手 | `contract_version=1.2.0`，商家名正确 |
| 登录 / `me` | 200，token 下发、管理员身份回显 |
| 建档（型号 / 客户 / 定价） | 201/200，ID 自增，价格 `100.00` |
| 建单 | 201，单号 `PS202611110001` 由服务端产出，总额 / 应收 / 已收 `200.00 / 200.00 / 0.00` |
| 幂等重放 | 同 `Idempotency-Key` 再提交 → 201 + `X-Idempotent-Replay: true`，订单仍只有 1 张 |
| 结算 | 提交 `50.00` → 审核通过 → 余额 `应 200 / 已收 50 / 未收 150` |
| 备注单 | 201，`BZ202611110001` 关联原单 |
| 审计 | `GET /audit` 同时拿到 `PRICE_CHANGE`（100→120）与 `SETTLE_AUDIT` |
| 打印 | `text/plain` 80mm 票据，`X-Print-Count` 递增 |
| 无 token 访问 | 401 + 统一错误体 |

## 全局不变量（本轮验收方式）

| 铁律 | 守卫 |
| --- | --- |
| 目录即模块 / 只经 Port | 各模块 `module.md` + 代码地图；S13 只持有 Port，C00 只持有 `ApiPaths` |
| 返回 DTO | 各模块边界测试（`*BoundaryTest`）断言出参类型 |
| 禁止跨模块访问表 | S13 `RouteTableTest` 无 SQL / repository；客户端模块扫描无 SQL |
| 跨模块事务 `TxRunner` | S13 三处跨 Port 写入同事务；S14 留痕并回事务；单测覆盖回滚 |
| 金额 / 单号 / 批号服务端产出 | 客户端模块测试断言无 `Money.ofPlain` 计算、无单号拼接 |
| 打印只在服务端 | `scan-buttons.ps1` + C12 只读打印状态 + C02 `ClientDenyPolicy` |
| 权限只在 S13 | 业务模块 Port 无身份入参；`E403` 只有 `AuthGate` 抛出 |
| 单号只经 S07 | 各模块无前缀字面量；网关不暴露取号接口 |
| 共享定义只在 /shared | 客户端 `ApiPaths` 只引用 shared DTO；关键词/路由 id 各有唯一真源 |
| SQL 只在各模块 repository | S09–S12、S14 的 SQL 各自收口；S13 无 SQL |
