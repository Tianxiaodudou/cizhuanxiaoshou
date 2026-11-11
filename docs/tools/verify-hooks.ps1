# Verifies the dsh hooks declared in docs/hooks/dsh-hooks.json (acceptance: "hooks 生效").
#
# Usage:
#   powershell -File docs/tools/verify-hooks.ps1            # run every hook
#   powershell -File docs/tools/verify-hooks.ps1 -Check scan
#   powershell -File docs/tools/verify-hooks.ps1 -Check code-map
#
# ASCII-only on purpose (PowerShell 5.1 reads no-BOM UTF-8 as ANSI).
[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [ValidateSet('all', 'scan', 'code-map')]
    [string]$Check = 'all'
)

$ErrorActionPreference = 'Stop'
$failures = New-Object System.Collections.Generic.List[string]

function Invoke-ScanHook {
    Write-Host "[hook] print-keyword-deny / button-scan"
    $script = Join-Path $RepoRoot 'docs/tools/scan-buttons.ps1'
    & $script -RepoRoot $RepoRoot | Out-Host
    if ($LASTEXITCODE -ne 0) { $failures.Add('scan-buttons.ps1 reported violations') }
    else { Write-Host "[hook] scan OK" -ForegroundColor Green }
}

function Test-CodeMapHook {
    Write-Host "[hook] code-map-freshness"
    $modules = @(
        'fpk-server/return-check', 'fpk-server/note', 'fpk-server/settlement', 'fpk-server/print',
        'fpk-server/api-gateway', 'fpk-server/observability',
        'android-app/core-network', 'android-app/core-session', 'android-app/core-ui', 'android-app/app-shell',
        'android-app/feature-login', 'android-app/feature-home', 'android-app/feature-order-entry',
        'android-app/feature-query', 'android-app/feature-today', 'android-app/feature-note',
        'android-app/feature-settle', 'android-app/feature-admin', 'android-app/feature-settings'
    )

    foreach ($module in $modules) {
        $moduleDir = Join-Path $RepoRoot $module
        if (-not (Test-Path $moduleDir)) { $failures.Add("missing module: $module"); continue }

        foreach ($required in @('module.md', 'code-map.md')) {
            if (-not (Test-Path (Join-Path $moduleDir $required))) {
                $failures.Add("$module missing $required")
            }
        }

        # every Kotlin package directory must have its own code-map.md
        $dirs = Get-ChildItem -Recurse -Directory (Join-Path $moduleDir 'src') -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\kotlin\\' -and $_.FullName -notmatch '\\build\\' }
        foreach ($dir in $dirs) {
            $map = Join-Path $dir.FullName 'code-map.md'
            if (-not (Test-Path $map)) { $failures.Add("missing code map: $($dir.FullName)") }
        }
    }

    if ($failures.Count -eq 0) { Write-Host "[hook] code maps OK" -ForegroundColor Green }
}

switch ($Check) {
    'scan' { Invoke-ScanHook }
    'code-map' { Test-CodeMapHook }
    'all' { Invoke-ScanHook; Test-CodeMapHook }
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host ("HOOKS FAILED ({0})" -f $failures.Count) -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    exit 1
}

Write-Host "HOOKS OK" -ForegroundColor Green
exit 0
