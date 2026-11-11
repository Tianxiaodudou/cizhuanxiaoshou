# Android 客户端开发规范（Jetpack Compose · Material 3）

> 本文件是 `android-app/**` 的唯一 UI / 架构规范依据。实现见 `android-app/app/`，
> 页面与文件映射见文末；服务端契约见 [docs/routes.md](../routes.md) 与 `/shared`。

## 1. 官方基线（Google Android 指南）

1. **单 Activity + Compose**：`MainActivity` 只做 `setContent {}`；页面用 `navigation-compose` 路由，禁止多 Activity 串联业务页。
2. **单向数据流（UDF）**：UI 只读 `State`，事件往上抛；网络/会话逻辑放在 repository / container，不写在 Composable 里。
3. **状态提升**：可变状态尽量放在页面级（或 ViewModel），叶子组件通过参数接收、通过 lambda 回调，组件本身无副作用。
4. **Material 3**：颜色、排版、形状全部走 `MaterialTheme`（`ColorScheme` / `Typography` / `Shapes`）；
   按钮用 `Button` / `FilledTonalButton` / `OutlinedButton`，列表用 `LazyColumn`，表单项用 `OutlinedTextField` + `supportingText`。
5. **Edge-to-edge**：`enableEdgeToEdge()` + `Scaffold` + `WindowInsets`，内容不被状态栏/导航栏遮挡。
6. **无障碍**：图标按钮必须有 `contentDescription`；可点区域 ≥ 48dp；错误文案通过 `supportingText` 或 `Snackbar` 展示，不只靠颜色。
7. **深色模式**：`JiancaiTheme` 跟随系统 `isSystemInDarkTheme()`，`dynamicColor` 默认关闭（门店配色固定）。
8. **版本与签名**：`versionName` 来自仓库根 `VERSION`（与 FPK 同源），`minSdk 26 / targetSdk 34`；
   当前 CI 出 **debug 签名** APK 供内测，正式上架前需补 release keystore + 签名配置。

## 2. 项目硬约束（高于官方建议）

| 约束 | 落地方式 |
| --- | --- |
| 金额 / 单号 / 批号**零计算** | 只展示服务端 DTO 里的字符串；单价取 `/prices`、合计取提交后的 `OrderDto`；客户端不做乘加 |
| 只调 S13 聚合接口 | 路径只能来自 C00 `ApiPaths`，页面不手写 URL |
| 权限由服务端 | 越权（`E403`）原样展示；客户端不判断角色 |
| 打印只在服务端 | 设置页只显示「由服务端 S12 打印」；客户端无打印按钮/渲染逻辑（SCAN 会拦） |
| token 只在 C01 | 页面不接触 token；会话统一由 `SessionGate` |
| 导航守卫 | 未登录只能进登录页；`AppNav` 启动时读 `SessionGate.destination()` |

## 3. 目录与文件映射（与预览页一一对应）

```text
android-app/app/src/main/kotlin/com/jiancai/android/app/
├── MainActivity.kt                 单 Activity，setContent
├── data/AppContainer.kt           ApiClient / SessionGate / 服务端地址（SharedPreferences）
├── navigation/AppNav.kt           路由表 + 底栏（查询 / 改当日单 / 设置）+ 守卫
├── ui/theme/Theme.kt              Material 3 主题（Color / Type / Shapes）
├── ui/common/Components.kt        通用组件（页面骨架、金额行、加载、错误条）
├── ui/login/LoginPage.kt          1. 登录
├── ui/home/HomePage.kt            2. 首页
├── ui/order/OrderFormComponent.kt 3–6 共用表单（差异只通过 kind 参数传入）
├── ui/order/DeliveryEntryPage.kt      3. 配送送货
├── ui/order/DeliveryReturnPage.kt     4. 配送退货
├── ui/order/PickupEntryPage.kt        5. 自提送货
├── ui/order/PickupReturnPage.kt       6. 自提退货
├── ui/query/QueryPage.kt          7. 查询（底栏 tab）
├── ui/today/TodayOrdersPage.kt    8. 改当日单列表（底栏 tab）
├── ui/today/TodayOrderEditPage.kt 9. 改当日单编辑
├── ui/note/NotePage.kt           10. 备注单
├── ui/note/SelectOrderPage.kt    11. 选原单
└── ui/settings/SettingsPage.kt   12. 设置（底栏 tab）
```

## 4. 页面口径

1. **登录**：账号 / 密码 / 服务端地址；地址保存后立即生效；登录成功才落会话。
2. **首页**：2×2 四个录入入口 + 备注单大按钮；顶栏显示当前账号；底栏 3 tab。
3. **四类录入**：共用 `OrderFormComponent`，差异只有 `OrderKind`（对应服务端 `OrderType`）：
   `配送送货→PURCHASE`、`配送退货→PURCHASE_RETURN`、`自提送货→DIRECT`、`自提退货→DIRECT_RETURN`。
   - 客户：按电话查（`/customers?phone=`），只读回显姓名/地址；
   - 明细：品类（`/categories`）+ 批号 + 数量；单价来自 `/prices`（只读）；
   - 本单合计 / 历史累计：服务端未提供预览与累计字段时显示「提交后由服务端计算」，
     提交成功后展示服务端返回的总额/应收；历史值取 `/customers/{id}/balance` 的应收；
   - 退货：先选原单（`/orders?phone=`）→ 可退批次（`/orders/{no}/returnable-batches`）→ 只允许退可退数量内的批次；
   - 自提人 / 自提时间：服务端契约暂无字段，界面只读占位并标注「待服务端支持」，不随请求发送。
4. **查询**：按电话查客户 + 订单列表；累计取服务端余额接口，不在本地累加。
5. **改当日单**：列表只展示 `/orders/today`；是否可改由服务端判定（`E2003` 原样提示）；编辑复用同一表单组件。
6. **备注单**：必须先选原单；差异类型/金额/说明提交 `CreateNoteCmd`（服务端为单条差异模型，暂不做逐行差异）。
7. **选原单**：按电话列出订单（近三日），点选回填，不允许手输单号。
8. **设置**：账号、服务端地址（可改）、打印状态（只读）、退出登录。

## 5. 质量门

- `./gradlew :android-app:app:assembleDebug` 通过，`lint` 无 error（warning 可接受）；
- 每页至少 1 个可执行路径（登录、首页跳转、四类录入、查询、改单、备注、设置）；
- `docs/tools/scan-buttons.ps1` 0 违规（客户端不得出现打印类文案，只读状态除外）；
- 真机（Android 11 / minSdk 26）安装启动，契约握手、登录、下单、查询全链路走通。
