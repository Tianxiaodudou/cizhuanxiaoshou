# 飞牛 fnOS 应用（FPK）开发规范

> 本文件是 `fpk-server` 在本项目里打 FPK 的**唯一规范依据**，实现见 [`packaging/fnos/`](../../packaging/fnos/)，
> 发版流程见 [docs/release.md](../release.md)。凡与本文件冲突的做法都视为 bug。

## 1. 包结构（与现网 FPK 一致）

```text
<appname>_<version>_<arch>.fpk        # 外层是 tar（不是 zip），顺序：manifest、app.tgz、cmd、config、图标
├── manifest                          # 纯文本 key = value，见 §2
├── app.tgz                           # 应用载荷（tar.gz），解包后直接落在 /var/apps/<appname>/
│   ├── bin/                          # 可执行 / 启动脚本
│   ├── lib/                          # 依赖（jar 等）
│   └── jre/                          # 需要时内置运行时（本项目用 Temurin 17 linux-x64）
├── cmd/                              # 生命周期脚本，必须可执行、LF 换行
│   ├── main                          # start|stop|restart|status
│   ├── install_init / install_callback
│   ├── upgrade_init / upgrade_callback
│   ├── config_init / config_callback
│   └── uninstall_init / uninstall_callback
├── config/
│   ├── privilege                     # 运行身份（run-as / username / groupname）
│   └── resource                      # 宿主能力声明（api-scope）
├── ICON.PNG                          # 64×64
└── ICON_256.PNG                      # 256×256
```

硬性约束：

1. **`manifest.checksum` = `md5(app.tgz)`**（32 位小写十六进制），安装器用它校验载荷。
2. 所有 `cmd/*` 必须是 **LF** 换行、**无 BOM**、有可执行位；`.gitattributes` 已锁 `eol=lf`。
   > 踩过坑：CRLF 会让 fnOS 的 `bash` 报 `$'\r': command not found`，BOM 会让 `#!/bin/bash` 失效。
3. `app.tgz` 里的路径**不要**再套一层目录：解包即得到 `bin/ lib/ jre/`。
4. 安装路径由 `TRIM_APPDEST` / `TRIM_PKGETC` / `TRIM_PKGVAR` / `TRIM_PKGTMP` / `TRIM_PKGHOME`
   注入，脚本禁止硬编码 `/var/apps/<name>`；本地手工安装时缺省回退到脚本所在目录。
5. 数据只写 `TRIM_PKGVAR`（库、台账、日志），配置只写 `TRIM_PKGETC`（密钥、端口、管理员口令），
   **不写系统目录、不改系统环境**。
6. 服务默认监听 `0.0.0.0:8080`（`etc/port` 可改），日志追加到 `var/server.log`，pid 落 `var/server.pid`。
7. 应用运行不需要 root：`config/privilege` 用 `run-as: package`；需要写 mounts 之类的才申请特权。

## 2. manifest 字段（本项目实际使用）

| 字段 | 值 | 说明 |
| --- | --- | --- |
| `appname` | `jiancai` | 唯一标识，决定安装目录 `/var/apps/jiancai` |
| `version` | 来自仓库根 `VERSION` | 与 APK 同一版本号 |
| `display_name` | 建材进销存 | 应用中心显示名 |
| `desc` | HTML 片段 | 简介（支持 `<p><b>` 等） |
| `platform` | `x86` | 飞牛包按平台区分，本项目只出 x86_64 |
| `source` | `thirdparty` | 第三方应用 |
| `maintainer` / `maintainer_url` | Tianxiaodudou | 维护者 |
| `distributor` | A鱼儿 | 分发者 |
| `os_min_version` | `1.2.0401` | 最低系统版本 |
| `ctl_stop` | `true` | 停止应用时调用 `cmd/main stop` |
| `changelog` | `<version>: ...` | 版本变更说明 |
| `checksum` | md5(app.tgz) | 必填，见 §1 |

## 3. 生命周期脚本约定

| 脚本 | 何时跑 | 本项目做什么 |
| --- | --- | --- |
| `install_init` | 安装前 | 预创建 `etc/var/tmp/home` 目录 |
| `install_callback` | 安装后 | 记一行安装日志（不阻塞） |
| `upgrade_init` / `upgrade_callback` | 升级前后 | 同上；升级**不动** `var/` 数据 |
| `config_init` / `config_callback` | 配置变更 | 同上 |
| `uninstall_init` / `uninstall_callback` | 卸载前后 | 同上；不删用户数据（由系统处理） |
| `main` | 每次启停 | `start` 起 JVM 网关、`stop` 杀进程、`status` 用退出码表达（0 运行 / 3 停止） |

`main start` 必须幂等（已在跑就直接返回 0）；`stop` 要等进程退出，超时再 `kill -9`。

## 4. 验收清单（打 FPK 前逐条过）

- [ ] `tar -tf x.fpk` 至少含 `manifest app.tgz cmd/main config/privilege ICON.PNG ICON_256.PNG`
- [ ] `md5sum app.tgz` 与 manifest 的 `checksum` 一致
- [ ] `cmd/*` 全部 `file` 显示 ASCII text（无 CRLF/BOM），权限 `755`
- [ ] 干净机器安装后 `cmd/main start` → `curl 127.0.0.1:8080/contract` 返回 200
- [ ] 升级（覆盖 `bin/lib/jre` + manifest）后 `var/jiancai.db` 数据仍在
- [ ] 卸载不残留进程（`pgrep -f GatewayMainKt` 为空）
- [ ] 版本号与同次发版的 APK 完全一致

## 5. 与飞牛系统的边界

- 本项目**不申请** `trim.*` 宿主能力（`config/resource.api-scope` 为空），不读写用户文件；
- 不做桌面图标/微应用（`desktop_uidir` / `micro_app` 不填），只作为后台服务运行；
- 端口冲突时由部署方改 `etc/port` 并重启应用，应用本身不做端口探测；
- 安装器（App Center）目前没有公开的 CLI 安装子命令，CI 只产出 FPK；验收机安装按
  [docs/release.md](../release.md) 的「安装 FPK」步骤执行。
