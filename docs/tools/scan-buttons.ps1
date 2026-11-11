# Button / keyword scan for the Android client (printing lives on the server, S12).
#
# Read-only: this script never edits business code.
#
# Keyword source of truth: android-app/core-ui/.../ui/UiKit.kt (ClientDenyPolicy):
#   FORBIDDEN_KEYWORDS      -> print actions that must never appear on the client
#   ALLOWED_READONLY_LABELS -> read-only labels such as "print status"
#   ALLOWED_IDENTIFIERS     -> read-only identifiers such as printTicket / PrintStatus
# The lists are parsed from that Kotlin file at run time; there is no copy here.
#
# NOTE: ASCII-only on purpose (PowerShell 5.1 reads no-BOM UTF-8 as ANSI).
[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'

$policyFile = Join-Path $RepoRoot 'android-app/core-ui/src/main/kotlin/com/jiancai/android/ui/UiKit.kt'
if (-not (Test-Path $policyFile)) { throw "deny policy source not found: $policyFile" }

$policyText = Get-Content -Raw -Encoding UTF8 $policyFile

function Get-StringList([string]$name) {
    $match = [regex]::Match($policyText, "$name[^=]*=\s*listOf\((?<body>[\s\S]*?)\)")
    if (-not $match.Success) { throw "cannot parse $name from UiKit.kt" }
    $items = @()
    foreach ($m in [regex]::Matches($match.Groups['body'].Value, '"([^"]+)"')) { $items += $m.Groups[1].Value }
    return $items
}

$keywords = Get-StringList 'FORBIDDEN_KEYWORDS'
$labels = Get-StringList 'ALLOWED_READONLY_LABELS'
$identifiers = Get-StringList 'ALLOWED_IDENTIFIERS'

Write-Host ("denied keywords ({0}): {1}" -f $keywords.Count, ($keywords -join ' | '))
Write-Host ("allowed read-only labels ({0}): {1}" -f $labels.Count, ($labels -join ' | '))
Write-Host ("allowed read-only identifiers ({0}): {1}" -f $identifiers.Count, ($identifiers -join ' | '))

$clientRoot = Join-Path $RepoRoot 'android-app'
$sources = @(Get-ChildItem -Recurse -File -Filter *.kt $clientRoot |
    Where-Object { $_.FullName -match '\\src\\main\\' })

$violations = New-Object System.Collections.Generic.List[string]
$buttonCount = 0
$annotationCount = 0

foreach ($file in $sources) {
    if ($file.Name -eq 'UiKit.kt') { continue }

    $lines = @(Get-Content -Encoding UTF8 $file.FullName)
    foreach ($index in 0..($lines.Count - 1)) {
        if ($index -lt 0) { continue }
        $line = [string]$lines[$index]
        $trimmed = $line.Trim()
        $number = $index + 1

        if ($line -match 'ButtonSpec\s*\(') { $buttonCount++ }
        if ($line -match '@UiButton\s*\(') { $annotationCount++ }

        # comments are neither code nor button labels
        if ($trimmed.StartsWith('*')) { continue }
        if ($trimmed.StartsWith('//')) { continue }
        if ($trimmed.StartsWith('/*')) { continue }

        # read-only display lines (print status / printed count / remaining count) are not actions
        $readOnlyLine = $false
        foreach ($label in $labels) { if ($line.Contains($label)) { $readOnlyLine = $true } }
        if ($readOnlyLine) { continue }

        $probe = $line
        foreach ($identifier in $identifiers) { $probe = $probe.Replace($identifier, '') }

        foreach ($keyword in $keywords) {
            if ($probe.IndexOf($keyword, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $rel = $file.FullName.Substring($RepoRoot.Length + 1)
                $violations.Add(("{0}:{1}  hit='{2}'  {3}" -f $rel, $number, $keyword, $line.Trim()))
            }
        }
    }
}

Write-Host ("scanned files: {0}" -f $sources.Count)
Write-Host ("ButtonSpec occurrences: {0}; @UiButton annotations: {1}" -f $buttonCount, $annotationCount)

if ($violations.Count -gt 0) {
    Write-Host ("FAILED: {0} violation(s); printing belongs to the server (S12)." -f $violations.Count) -ForegroundColor Red
    foreach ($violation in $violations) { Write-Host "  $violation" -ForegroundColor Red }
    exit 1
}

Write-Host "OK: the client has no print button / print wording (printing lives on the server, S12)." -ForegroundColor Green
exit 0
