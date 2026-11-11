# 发版与安装（FPK / APK）

## 版本号：一处递增，两端共用

- 唯一来源：仓库根 [`VERSION`](../VERSION)（纯文本 semver，如 `1.0.7`）。
- 服务端 FPK 的 manifest `version`、客户端 APK 的 `versionName` **都读同一个文件**；
  APK 的 `versionCode` 由 semver 换算（`major*10000 + minor*100 + patch`）。
- 递增只发生在 GitHub 工作流里：`workflow_dispatch` 选 `patch` / `minor` 时，version job 先改 `VERSION`
  并提交回 `main`（`chore(release): 版本号递增到 x.y.z [skip ci]`），再打 `vX.Y.Z` 标签、构建、发 Release。
- 推 `v*` 标签触发时不改 `VERSION`，直接按标签版本构建（用于补包 / 重打）。

## 工作流：[.github/workflows/release.yml](../.github/workflows/release.yml)

```text
version  → 计算版本（必要时提交 VERSION 递增 + 推 tag）
server   → ./gradlew :fpk-server:app:installDist
           + Temurin 17 linux-x64 JRE（adoptium API）
           + packaging/fnos/build-fpk.sh → jiancai_<ver>_x86_64.fpk
client   → sdkmanager 装 platforms;android-34 + build-tools;34.0.0
           + ./gradlew :android-app:app:assembleDebug → jiancai-client_<ver>.apk
publish  → 下载两个 artifact，创建 GitHub Release（tag vX.Y.Z）并挂上 .fpk / .apk
```

产物命名：

| 产物 | 命名 | 说明 |
| --- | --- | --- |
| 服务端 | `jiancai_<version>_x86_64.fpk` | 内置 JRE 17，fnOS 可直接运行，默认端口 8080 |
| 客户端 | `jiancai-client_<version>.apk` | debug 签名，minSdk 26；真机 adb install 即可 |

## FPK 结构（与飞牛现网包一致）

```text
manifest              appname / version / display_name / desc / platform / checksum(md5 of app.tgz) ...
app.tgz               载荷：bin/（启动脚本） lib/（jar） jre/（Temurin JRE 17）
cmd/main              start|stop|restart|status
cmd/install_init 等    安装 / 升级 / 配置 / 卸载钩子（只建目录、记一行日志）
config/privilege      运行身份（package / jiancai:jiancai）
config/resource       宿主能力声明（本应用不需要额外 API）
ICON.PNG / ICON_256.PNG
```

- `app.tgz` 的 **md5 就是 manifest 的 checksum**（校验脚本在 `build-fpk.sh` 里生成）。
- 所有 shell 脚本必须是 **LF**（`.gitattributes` 已锁 `eol=lf`）；CRLF 会让 fnOS 上的 `bash` 直接报
  `$'\r': command not found`。
- 运行数据：`/var/apps/jiancai/{etc,var,tmp,home}`；`etc/secret.key`（token 签名密钥，首次自动生成）、
  `etc/port`（默认 8080）、`etc/admin.password`（存在时首启创建 admin）、`var/jiancai.db`（SQLite）、
  `var/server.log`。

## 安装 FPK（fnOS）

飞牛 App Center 的 CLI 只有 `trim_app_center`（无公开 install 子命令），因此本次验收用「按 FPK 结构手工落盘 + 生命周期脚本」安装：

```bash
sudo mkdir -p /var/apps/jiancai && cd /var/apps/jiancai
sudo tar -xf /tmp/jiancai_<ver>_x86_64.fpk
sudo tar -xzf app.tgz && sudo rm app.tgz
sudo mkdir -p etc var tmp home
echo 8080 | sudo tee etc/port
echo <admin密码> | sudo tee etc/admin.password   # 可选：首启创建 admin
sudo chmod +x cmd/*
sudo bash cmd/main start
curl -s http://127.0.0.1:8080/contract
```

## 安装 APK（Android 真机）

```bash
adb install -r jiancai-client_<ver>.apk
adb shell am start -n com.jiancai.android.app/.MainActivity
```

客户端默认服务端地址 `http://127.0.0.1:8080`；真机联调时用
`adb reverse tcp:8080 tcp:8080` 把它映射到电脑，再由电脑上的转发进程连到 fnOS（见下）。

## 联调拓扑（本次实测）

```text
手机 127.0.0.1:8080
   └─ adb reverse ─→ 电脑 127.0.0.1:8080
        └─ Node TCP 转发 ─→ fnOS 192.168.80.129:8080
             └─ FPK 内的 JVM 网关（S13，内置 JRE）
```

- fnOS 的 sshd 关闭了 TCP 转发（`administratively prohibited`），所以用 `docs/tools` 之外的一个
  临时 Node 转发脚本（不提交）替代 `ssh -L`。
- 手机与 fnOS 不在同一网段（192.168.1.x / 192.168.80.x），必须经电脑中转。

## 真机验证结果（2026-11-11，v1.0.8）

| 步骤 | 结果 |
| --- | --- |
| FPK 安装（fnOS 192.168.80.129） | `1.0.8` 运行中；`/contract` 返回 contractVersion 1.2.0、商家「建材进销存」 |
| FPK 升级（保留数据） | 1.0.7 → 1.0.8 只替换 `bin/lib/jre` + manifest，`var/jiancai.db` 保留，订单仍在 |
| APK 安装（Redmi K40 / Android 11） | `jiancai-client_1.0.8.apk` 安装成功，启动器名称「建材进销存」 |
| App 契约握手 | 界面显示「契约版本=1.2.0，商家=建材进销存」（adb reverse → PC 转发 → fnOS） |
| App 登录 | admin 登录成功，进入首页（5 入口） |
| App 下单 | 查客户 → 提交订单，回显「单号=PS202611110001，总额=200.00，应收=200.00」 |
| 服务端核对 | `/orders/today` 返回 1 单：`PS202611110001 purchase 200.00 200.00 created_by=1 lines=1` |

截图留在本机（未入库）：`.tools/app-order-success.png`、`.tools/app-contract.png`。

## 第六轮：Compose 客户端 + 新测试服务器（v1.0.12）

| 项目 | 结果 |
| --- | --- |
| 客户端 UI 框架 | 全量 **Jetpack Compose + Material 3**（单 Activity + navigation-compose），12 页按预览页落文件，3–6 共用 `OrderFormComponent` |
| 规范文档 | [docs/specs/fnos-fpk.md](specs/fnos-fpk.md)、[docs/specs/android-compose.md](specs/android-compose.md) |
| CI 产物 | Release `v1.0.12`：`jiancai-client_1.0.12.apk`（Compose）+ `jiancai_1.0.12_x86_64.fpk` |
| 新测试机 | fnOS `192.168.1.20`（Debian 12 / x86_64），FPK 已升级到 1.0.12，`/contract` = 1.2.0，旧订单保留 |
| 真机 | Redmi K40 安装 CI 产物 1.0.12，登录 `http://192.168.1.20:8080` → 首页 → 配送送货（李四 / 800×800 浅灰 / 浅灰A12 / 20 件 / 单价 45.00）→ 提交 `PS202611110001` 总额 900.00；查询、改当日单、设置页均实测 |
| 服务端核对 | `/orders/today` = 1 单 `PS202611110001 purchase 900.00`，行 20 件 × 45.00 |


