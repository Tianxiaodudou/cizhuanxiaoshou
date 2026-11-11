# hooks 索引

配置在 `dsh-hooks.json`，说明见 [docs/CODEMAPS/conventions.md](../CODEMAPS/conventions.md) 的「hooks」一节。

| hook | 触发 | 命令 | 失败策略 |
| --- | --- | --- | --- |
| `print-keyword-deny` | pre-commit | `docs/tools/scan-buttons.ps1` | block |
| `button-scan` | pre-push | `docs/tools/scan-buttons.ps1` | warn |
| `file-header-check` | pre-push | `docs/tools/check-file-headers.ps1` | warn |

本地验证：`powershell -File docs/tools/verify-hooks.ps1` → `HOOKS OK`。
