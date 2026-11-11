# Verifies the dsh hooks declared in docs/hooks/dsh-hooks.json (acceptance: "hooks 生效").
#
# Usage:
#   powershell -File docs/tools/verify-hooks.ps1
#   powershell -File docs/tools/verify-hooks.ps1 -Check scan
#   powershell -File docs/tools/verify-hooks.ps1 -Check headers
#   powershell -File docs/tools/verify-hooks.ps1 -Check docs
#
# ASCII-only on purpose (PowerShell 5.1 reads no-BOM UTF-8 as ANSI).
[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [ValidateSet('all', 'scan', 'headers', 'docs')]
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

function Invoke-HeaderHook {
    Write-Host "[hook] file-header-check"
    $script = Join-Path $RepoRoot 'docs/tools/check-file-headers.ps1'
    & $script -RepoRoot $RepoRoot | Out-Host
    # warning-only hook: never fails the build
    Write-Host "[hook] file-header-check done (warning-only)" -ForegroundColor Green
}

function Test-DocsHook {
    Write-Host "[hook] code-map / module docs"
    $codemaps = @('README.md', 'architecture.md', 'backend.md', 'frontend.md', 'conventions.md')
    foreach ($name in $codemaps) {
        $path = Join-Path $RepoRoot "docs/CODEMAPS/$name"
        if (-not (Test-Path $path)) { $failures.Add("missing docs/CODEMAPS/$name") }
    }

    $modules = @(
        'shared', 'docs',
        'fpk-server/return-check', 'fpk-server/note', 'fpk-server/settlement', 'fpk-server/print',
        'fpk-server/api-gateway', 'fpk-server/observability',
        'android-app/core-network', 'android-app/core-session', 'android-app/core-ui', 'android-app/app-shell',
        'android-app/feature-login', 'android-app/feature-home', 'android-app/feature-order-entry',
        'android-app/feature-query', 'android-app/feature-today', 'android-app/feature-note',
        'android-app/feature-settle', 'android-app/feature-admin', 'android-app/feature-settings'
    )
    foreach ($module in $modules) {
        $moduleDir = Join-Path $RepoRoot $module
        if (-not (Test-Path $moduleDir)) { $failures.Add("missing module dir: $module"); continue }
        if (-not (Test-Path (Join-Path $moduleDir 'module.md'))) {
            $failures.Add("$module missing module.md")
        }
    }

    if ($failures.Count -eq 0) { Write-Host "[hook] docs OK" -ForegroundColor Green }
}

switch ($Check) {
    'scan' { Invoke-ScanHook }
    'headers' { Invoke-HeaderHook }
    'docs' { Test-DocsHook }
    'all' { Invoke-ScanHook; Invoke-HeaderHook; Test-DocsHook }
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host ("HOOKS FAILED ({0})" -f $failures.Count) -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    exit 1
}

Write-Host "HOOKS OK" -ForegroundColor Green
exit 0
