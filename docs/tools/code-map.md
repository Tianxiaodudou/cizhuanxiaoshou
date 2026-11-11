# Code map · docs/tools

## 文件

| 文件 | 说明 |
| --- | --- |
| `gen-code-map.ps1` | 生成全层级 `code-map.md`（中文）+ 每个 `.kt` 文件头部的行号级代码地图 |
| `code-map-template.json` | 生成器的中文文案模板（脚本本体保持 ASCII，兼容 PowerShell 5.1） |
| `scan-buttons.ps1` | 按钮 / 打印关键词扫描；关键词真源取自 C02 `ClientDenyPolicy`；违规退出码 1 |
| `verify-hooks.ps1` | hooks 自检：跑扫描 + 校验每个目录都有 `code-map.md` |
| `device-e2e.sh` | 真机联调脚本：手机 shell 里用 `curl` 打真实网关（配合 `adb reverse`） |

## 约定

1. 脚本**只读业务代码**；生成器只写 `code-map.md` 与源码文件头注释；
2. 脚本本体保持 ASCII：PowerShell 5.1 会把无 BOM 的 UTF-8 当 ANSI 读，中文文案一律走
   `code-map-template.json`（`-Encoding UTF8` 读取）；
3. 唯一真源：打印关键词 → `ClientDenyPolicy`；路由 id → `AppRoutes`；接口路径 → `ApiPaths`；
4. 改完代码后重跑 `gen-code-map.ps1`，否则文件头里的行号会漂移。
