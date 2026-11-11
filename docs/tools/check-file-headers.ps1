# 文件头代码地图检查
#
# Scans every Kotlin source file and checks that a `@file` marker exists within
# the first 10 lines. Default mode only warns (exit 0); use -Strict to fail.
#
# Usage:
#   powershell -File docs/tools/check-file-headers.ps1
#   powershell -File docs/tools/check-file-headers.ps1 -Strict
#
# ASCII-only on purpose (PowerShell 5.1 reads no-BOM UTF-8 as ANSI).
[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string[]]$Roots = @('shared', 'fpk-server', 'android-app'),
    [switch]$Strict
)

$ErrorActionPreference = 'Stop'
$excluded = @('build', '.gradle', '.git', '.tools', '.kotlin', 'node_modules')
$missing = New-Object System.Collections.Generic.List[string]
$checked = 0

foreach ($root in $Roots) {
    $rootDir = Join-Path $RepoRoot $root
    if (-not (Test-Path $rootDir)) { continue }

    foreach ($file in @(Get-ChildItem -Recurse -File -Filter *.kt $rootDir)) {
        $parts = $file.FullName.Split('\')
        $skip = $false
        foreach ($part in $parts) { if ($excluded -contains $part) { $skip = $true } }
        if ($skip) { continue }

        $checked++
        $head = @(Get-Content -Encoding UTF8 $file.FullName -TotalCount 10)
        $hasFile = $false
        foreach ($line in $head) { if ($line.Contains('@file ')) { $hasFile = $true } }
        if (-not $hasFile) { $missing.Add($file.FullName.Substring($RepoRoot.Length + 1)) }
    }
}

Write-Host ("checked={0}  missing=@file:{1}" -f $checked, $missing.Count)

if ($missing.Count -gt 0) {
    Write-Host "Files without a @file header in the first 10 lines:" -ForegroundColor Yellow
    foreach ($item in $missing) { Write-Host "  $item" -ForegroundColor Yellow }
    if ($Strict) { exit 1 }
    Write-Host "WARN: add the header with docs/tools/gen-code-map.ps1 (warning only, build not blocked)." -ForegroundColor Yellow
    exit 0
}

Write-Host "OK: every Kotlin file declares @file in its header." -ForegroundColor Green
exit 0
