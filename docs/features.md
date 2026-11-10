# 功能清单

## 阶段 1 · 契约与地基
先建唯一真源契约、唯一数据库内核、单号与部署/鉴权底座。串行为主。

### SHARED · shared 契约层
两端唯一真源：DTO、枚举、错误码、单号前缀、单位与装箱规格，带 contractVersion。

### S00 · db-core
唯一 SQLite 连接、事务、迁移、:memory: 测试库。

### S07 · serial
单号生成：前缀+年+月日+全局序号，事务取号+唯一约束兜底。

### S01 · config
部署级商家名称、部署配置（客户端不可传）。

### S02 · auth
登录、token 签发与校验、账号密码。

### S03 · account
账号 CRUD（仅管理员）。

## 阶段 2 · 业务主链
customer → category → pricing → order 主链。

### S04 · customer
客户建档、按电话查询、地址快照。

### S05 · category
品类/型号库 CRUD（仅管理员）、批号类型。

### S06 · pricing
恒定值（客户+型号+批号）、当日可改、跨日锁、管理员改价留痕。

### S08 · order
订单抬头/明细、幂等、乐观锁、改当日单、应收派生。

## 阶段 3 · 旁链与网关
退单/备注/结算/打印可并行，最后加网关与可观测。

### S09 · return-check
可退数量计算、批次校验（规则独立）。

### S10 · note
差异备注单（关联原单、不改原单、作废）。

### S11 · settlement
结算记录、当场判定、审核、已收/未收读模型。

### S12 · print
打印渲染（80mm、五列/双行、换算、打印次数）。

### S13 · api-gateway
路由、权限拦截、错误码映射、幂等键校验。

### S14 · observability
日志、审计留痕（改价、审核）。

## 阶段 4 · 客户端（Android）
core-* → feature-* 顺序，feature-order-entry 优先。

### C00 · core-network
HTTP 客户端、token 注入、错误码统一解析。

### C01 · core-session
登录态、导航守卫（唯一 token 判断点）。

### C02 · core-ui
通用组件、主题、按钮 @ui-button 注解。

### C03 · app-shell
底栏 3 tab、路由表、首页容器。

### C04 · feature-login
登录页、服务端地址可改。

### C05 · feature-home
5 按钮布局。

### C06 · feature-order-entry
4 类录入页共用 OrderFormComponent，只传 orderType。

### C07 · feature-query
按电话查历史，只调聚合接口。

### C08 · feature-today
改当日单列表 + 编辑（复用录入页壳）。

### C09 · feature-note
备注单 + 选原单（SelectOrderPage）。

### C10 · feature-settle
结算上传，成功/失败当场回显。

### C11 · feature-admin
型号库、价格、结算审核、账号四个子页。

### C12 · feature-settings
客户端配置 + 只读打印状态。

## 阶段 5 · 收尾与治理
文档、按钮扫描脚本与 dsh hooks。

### DOCS · 文档
docs/routes.md、docs/features.md 与扫描产物。

### SCAN · 按钮扫描脚本
按钮扫描脚本（对应打印关键词 deny hooks）。

### HOOKS · dsh hooks
dsh hooks 配置。
