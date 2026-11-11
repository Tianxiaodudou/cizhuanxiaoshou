# 建材系统 · 剩余 22 个模块「单 agent 一次完成」总任务

【硬性要求】你是「建材系统」剩余模块的专职 agent。本次任务要求在**同一个会话里一次跑完**下列 22 个模块，按第 3 节的顺序和工作方式推进；已完成模块只读，不得改动，如果必须要对已完成模块进行改动，要所有任务全部完成之后在输出的结果总结里给出改造方案，任务完成之前没有人类下令不得对已完成模块进行改动。

---

## 1. 工作区与环境

- 根目录：`D:\建材系统`（Gradle 多模块 Kotlin 工程，Kotlin DSL）
- 契约版本：`contractVersion = 1.0.0`（变更必须递增）
- 已完成模块（**只读，一行都不许改**）：SHARED、S00 db-core、S07 serial、S01 config、S02 auth、S03 account、S04 customer、S05 category、S06 pricing、S08 order
- 已存在但需你接手/完善的目录：`fpk-server/return-check`(S09)、`fpk-server/note`(S10)、`fpk-server/settlement`(S11)
- 尚不存在、需要新建：`fpk-server/print`(S12)、`fpk-server/api-gateway`(S13)、`fpk-server/observability`(S14)、`android-app/**`(C00–C12)
- 构建：每个模块用 `./gradlew :<模块路径>:test` 单独验证；新增模块必须先在 `settings.gradle.kts` 里 `include` 再引用
- 文档：每个模块目录下必须有 `module.md`（职责 / Port / 依赖 / 表归属 / 写入范围 / 验收对照 / 变更记录）,每个模块目录下也必须有代码地图文档,只细化到目录。然后子目录里面再有代码地图文档

## 2. 十条铁律（全程不可违背）

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

## 3. 执行协议（一次跑完的节奏）

1. **串行推进**：一次只动一个模块的目录；一个模块「代码 + 单测 + `module.md` + gradle test 通过」再做下一个。
2. **阶段门自带**：先把阶段 3（S09→S14）全部做完并各自测试通过，再进阶段 4（C00–C12），最后阶段 5（DOCS/SCAN/HOOKS）。
3. **越界即停手**：如果发现必须修改上面「已完成模块」或其它模块的目录，**不要动手**，把「要改什么、为什么、影响面」记进最后的待确认清单，继续推进本模块能做的部分。
4. **契约变更走 /shared**：只在 `/shared` 增字段/枚举并递增 `contractVersion`，同时在该模块 `module.md` 记录；不得在业务模块里写魔法字符串或私建 DTO。
5. **每模块自测**：`./gradlew :<模块>:test` 必须绿；测试要覆盖 Card 里「验收」列的每一条，业务不变量用单测守住。
6. **不要顺手重构**：不格式化无关文件、不改已完成模块的签名、不动 build 脚本的既有约定（只允许新增 `include` 与依赖行）。
7. **断点可续**：每完成一个模块，在 `docs/进度-单agent.md` 追加一行「模块 / 完成时间 / 测试结果 / 遗留」，便于中断后继续。
8. **全部完成后**输出第 5 节的收尾报告。

## 4. 模块清单（按顺序执行）

### 阶段 3 · 周边功能（先全部做完）

#### S09 · 退单校验 `fpk-server/return-check/`
- 职责：可退数量计算、批次校验（规则独立）。
- 实现 Port：`ReturnCheckPort.availableBatches / validate`
- 依赖：`S08` order → `OrderPort`（只读订单，禁止直连 order 表）
- 禁止：直接访问 order 表；修改订单数据。
- 验收：只通过 OrderPort 读；规则可独立升级。

#### S10 · 差异备注 `fpk-server/note/`
- 职责：差异备注单（关联原单、不改原单、作废）。
- 实现 Port：`NotePort.create / void`
- 依赖：`S08` order → `OrderPort`
- 禁止：修改原单；直接访问 order 表。
- 验收：原单不被改动；作废用例通过。

#### S11 · 结算 `fpk-server/settlement/`
- 职责：结算记录、当场判定、审核、已收/未收读模型。
- 实现 Port：`SettlePort.submit / audit / getBalance`
- 依赖：`S08` order（应收从 OrderPort 重新读取）、`S03` account（审核人）
- 禁止：直接访问 order 表；改动已收金额；本地缓存应收。
- 验收：审核流通过；已收金额不被改动。

#### S12 · 打印 `fpk-server/print/`
- 职责：打印渲染（80mm、五列/双行、换算、打印次数）。
- 实现 Port：`PrintPort.render(orderNo / noteNo)`
- 依赖：`S08` order（OrderPort 只读）、`S01` config（商家名称）
- 禁止：客户端打印；直接访问 order 表；在别处重复实现渲染。
- 验收：80mm 渲染正确；打印次数记录；客户端 deny 生效。

#### S13 · 服务网关 `fpk-server/api-gateway/`
- 职责：路由、权限拦截、错误码映射、幂等键校验。
- 实现 Port：HTTP 路由（对客户端暴露的唯一入口）
- 依赖：S02、S03、S04、S05、S06、S07、S08、S09、S10、S11、S12 的 Port
- 禁止：实现业务逻辑（只转发 / 拦截 / 映射）。
- 验收：权限在 API 层拦截；错误码统一；幂等键校验。

#### S14 · 日志审计 `fpk-server/observability/`
- 职责：日志、审计留痕（改价、审核）。
- 实现 Port：`AuditPort`
- 依赖：`S00` db-core
- 禁止：参与业务决策。
- 验收：改价 / 审核有留痕。

### 阶段 4 · 手机端（阶段 3 全绿后再开始）

> 客户端统一约束：金额、单号、批号只展示不计算；只调聚合接口；打印相关关键词全局 deny；token 判断只在 C01 一处。

#### C00 · 网络请求 `android-app/core-network/`
- 职责：HTTP 客户端、token 注入、错误码统一解析。
- 实现：`ApiClient`
- 禁止：业务逻辑；金额/单号计算。
- 验收：错误码统一解析；token 自动注入。

#### C01 · 登录状态 `android-app/core-session/`
- 职责：登录态、导航守卫（唯一 token 判断点）。
- 实现：`SessionGate`
- 依赖：`C00`
- 禁止：其它地方判断 token。
- 验收：token 判断仅此一处。

#### C02 · 通用界面 `android-app/core-ui/`
- 职责：通用组件、主题、按钮 `@ui-button` 注解。
- 实现：组件库
- 禁止：耦合具体业务。
- 验收：组件可复用。

#### C03 · 应用外壳 `android-app/app-shell/`
- 职责：底栏 3 tab、路由表、首页容器。
- 实现：`NavGraph`
- 依赖：C05–C12
- 禁止：feature 之间互相 import UI。
- 验收：路由跳转正常。

#### C04 · 登录页 `android-app/feature-login/`
- 职责：登录页、服务端地址可改。
- 实现：`LoginRoute`
- 依赖：`C01`
- 禁止：自行保存 token。
- 验收：登录可用；服务端地址可改。

#### C05 · 首页 `android-app/feature-home/`
- 职责：5 按钮布局。
- 实现：`HomeRoute`
- 依赖：`C02`
- 禁止：业务计算。
- 验收：按钮跳转正常。

#### C06 · 下单录入 `android-app/feature-order-entry/`
- 职责：4 类录入页共用 `OrderFormComponent`，只传 `orderType`。
- 实现：`OrderEntryRoute(orderType)`
- 依赖：`C00`
- 禁止：客户端算金额 / 单号。
- 验收：4 类页共用组件；提交走服务端。

#### C07 · 历史查询 `android-app/feature-query/`
- 职责：按电话查历史，只调聚合接口。
- 实现：`QueryRoute`
- 依赖：`C00`
- 禁止：本地拼 SQL / 直连。
- 验收：只调聚合接口。

#### C08 · 今日改单 `android-app/feature-today/`
- 职责：改当日单列表 + 编辑（复用录入页壳）。
- 实现：`TodayRoute`
- 依赖：`C06`
- 禁止：跨日改单（由服务端锁）。
- 验收：复用录入壳；跨日不可改。

#### C09 · 差异备注单 `android-app/feature-note/`
- 职责：备注单 + 选原单（`SelectOrderPage`）。
- 实现：`NoteRoute`
- 依赖：`C00`
- 禁止：修改原单。
- 验收：备注单正确关联原单。

#### C10 · 结算上传 `android-app/feature-settle/`
- 职责：结算上传，成功/失败当场回显。
- 实现：`SettleRoute`
- 依赖：`C00`
- 禁止：客户端判定结算。
- 验收：上传与回显正确。

#### C11 · 管理后台 `android-app/feature-admin/`
- 职责：型号库、价格、结算审核、账号四个子页。
- 实现：`AdminRoute`
- 依赖：`C00`
- 禁止：绕过服务端权限。
- 验收：4 子页可用；权限由服务端把关。

#### C12 · 设置 `android-app/feature-settings/`
- 职责：客户端配置 + 只读打印状态。
- 实现：`SettingsRoute`
- 依赖：`C00`
- 禁止：客户端打印；打印关键词 deny。
- 验收：打印状态只读。

### 阶段 5 · 收尾

#### DOCS · 说明文档 `/docs`
- 职责：`docs/routes.md`、`docs/features.md` 与扫描产物。
- 写入范围：`docs/**`
- 禁止：与实现脱节。
- 验收：文档与代码实现一致。

#### SCAN · 按钮扫描 `/docs`
- 职责：按钮扫描脚本（对应打印关键词 deny hooks）。
- 写入范围：`docs/**`
- 禁止：只读扫描，禁止改业务代码。
- 验收：能扫出违规关键词。

#### HOOKS · 自动化 `/docs`
- 职责：dsh hooks 配置。
- 写入范围：`docs/**`
- 禁止：改变模块边界。
- 验收：hooks 生效。

## 5. 完成判据与收尾报告

**Definition of Done**
- [ ] 22 个模块目录齐全，各有 `module.md`
- [ ] 每模块 `./gradlew :<模块>:test` 通过（客户端模块若环境缺 Android SDK，退化为 JVM 单测并在报告标注）
- [ ] 全局 `./gradlew build` 通过（Android 部分单独说明）
- [ ] 无跨模块越界：不 import 别的模块 repository/表，SQL 只在本模块 repository
- [ ] `/shared` 与 `contractVersion` 一致，无魔法字符串
- [ ] `docs/进度-单agent.md` 有 22 行进度记录

**收尾报告格式（只报这三块，不要贴大段代码）**
1. 完成清单：模块 → 新增/修改文件 → 测试结果（用例数）
2. 未完成/受阻：模块 → 原因（环境缺失、依赖未就绪等）
3. 待确认：需要改别的模块的地方（模块 → 要改什么 → 为什么）
