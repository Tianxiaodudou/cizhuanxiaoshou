# Generates the code maps required by the task (Chinese content, every directory level)
# and writes a per-file header "code map" into every Kotlin source file.
#
#   <dir>/code-map.md            for every directory (recursive)
#   <file>.kt                    header comment: line number / kind / symbol
#
# The script is ASCII-only because PowerShell 5.1 reads no-BOM UTF-8 as ANSI;
# all Chinese text lives in code-map-template.json (loaded with -Encoding UTF8).
# Re-running is idempotent: an existing header block is replaced.
[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string[]]$Roots = @('shared', 'fpk-server', 'android-app')
)

$ErrorActionPreference = 'Stop'
$t = Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot 'code-map-template.json') | ConvertFrom-Json

$excluded = @('build', '.gradle', '.git', '.tools', '.kotlin', 'node_modules')
$script:mapCount = 0
$script:headerCount = 0

function Expand-Template {
    param([string]$Text, [hashtable]$Values)
    $result = $Text
    foreach ($key in $Values.Keys) { $result = $result.Replace('{' + $key + '}', [string]$Values[$key]) }
    return $result
}

function Get-FirstDocLine {
    param([string]$Path)
    $lines = Get-Content -Encoding UTF8 $Path
    $inDoc = $false
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if (-not $inDoc) {
            if ($trimmed.StartsWith('/**')) { $inDoc = $true }
            continue
        }
        if ($trimmed.StartsWith('*/')) { break }
        $text = $trimmed.TrimStart('*').Trim()
        if ($text -and -not $text.StartsWith('@')) { return $text }
    }
    return ''
}

function Get-DelegationRole {
    param([string]$Name)
    if ($t.roles.PSObject.Properties.Name -contains $Name) { return $t.roles.$Name }
    return ''
}

# ---------------- file headers ----------------

function Get-Symbols {
    param([string[]]$Lines)
    $modifiers = 'private|internal|public|protected|override|suspend|inline|operator|infix|tailrec|external|expect|actual|abstract|open|final|data|sealed|value|annotation|enum|companion|fun'
    $pattern = '^\s*(?:(?:' + $modifiers + ')\s+)*(fun|class|object|interface|typealias)\s+'
    $symbols = New-Object System.Collections.Generic.List[object]

    for ($i = 0; $i -lt $Lines.Count; $i++) {
        $line = $Lines[$i]
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        if ($trimmed.StartsWith('*') -or $trimmed.StartsWith('//') -or $trimmed.StartsWith('/*') -or $trimmed.StartsWith('*/')) { continue }
        if (-not ($line -match $pattern)) { continue }

        $kind = $Matches[1]
        $rest = $trimmed.Substring($trimmed.IndexOf($kind) + $kind.Length).Trim()
        $name = ''
        $nameMatch = [regex]::Match($rest, '^(?:<[^>]*>\s*)?(?:[\w\.\?]+\s*\.\s*)?(\w+)')
        if ($nameMatch.Success) { $name = $nameMatch.Groups[1].Value }
        if (-not $name) { $name = '?' }

        $kindCn = $t.headerKinds.$kind
        if (-not $kindCn) { $kindCn = $kind }

        $symbols.Add([pscustomobject]@{ Line = $i + 1; Kind = $kindCn; Name = $name })
    }

    return $symbols
}

function Set-FileHeader {
    param([string]$Path)

    $lines = @(Get-Content -Encoding UTF8 $Path)
    if ($lines.Count -eq 0) { return }

    # strip an existing generated header (idempotent regeneration)
    if ($lines.Count -ge 2 -and $lines[0].Trim() -eq '/*' -and $lines[1].Contains($t.headerMarker)) {
        $end = -1
        for ($i = 1; $i -lt $lines.Count; $i++) {
            if ($lines[$i].Trim() -eq '*/') { $end = $i; break }
        }
        if ($end -gt 0) {
            $next = $end + 1
            if ($next -lt $lines.Count -and $lines[$next].Trim() -eq '') { $next++ }
            $lines = @($lines[$next..($lines.Count - 1)])
        }
    }
    if ($lines.Count -eq 0) { return }

    $symbols = Get-Symbols -Lines $lines
    if ($symbols.Count -eq 0) { return }

    # header size = 3 fixed lines + entries + closing + blank line
    $shift = $symbols.Count + 5

    $output = New-Object System.Collections.Generic.List[string]
    $output.Add('/*')
    $output.Add(' * ' + $t.headerTitle)
    $output.Add(' * ' + $t.headerNote)
    foreach ($symbol in $symbols) {
        $number = $symbol.Line + $shift
        $output.Add(($t.headerLine -f $number, $symbol.Kind, $symbol.Name))
    }
    $output.Add(' */')
    $output.Add('')
    foreach ($line in $lines) { $output.Add($line) }

    Set-Content -Path $Path -Value $output -Encoding utf8
    $script:headerCount++
}

# ---------------- directory maps ----------------

function Get-RoleFor {
    param([string]$RelativePath)

    if (-not $RelativePath) { return $t.roles.root }
    $name = Split-Path $RelativePath -Leaf
    if (-not $name) { return $t.roles.root }
    $known = Get-DelegationRole $name
    if ($known) { return $known }
    if ($RelativePath -match '/kotlin/com/jiancai/android/\w+$') { return $t.roles.clientPackage }
    if ($RelativePath -match '/kotlin/com/jiancai/\w+$') { return $t.roles.serverPackage }
    if ($RelativePath -match '/src/main/kotlin(/|$)') { return $t.roles.main }
    if ($RelativePath -match '/src/test/kotlin(/|$)') { return $t.roles.test }
    if ($RelativePath -match '/src(/|$)') { return $t.roles.src }
    if ($RelativePath -match '^fpk-server/[^/]+$') { return $t.roles.serverModule }
    if ($RelativePath -match '^android-app/[^/]+$') { return $t.roles.clientModule }
    if ($RelativePath -match '/kotlin/com/jiancai/android/\w+$') { return $t.roles.clientPackage }
    if ($RelativePath -match '/kotlin/com/jiancai/\w+$') { return $t.roles.serverPackage }
    return $t.roles.structural
}

function New-DirMap {
    param([string]$Dir, [string]$RelativePath)

    $title = if ($RelativePath) { Expand-Template $t.moduleTitle @{ path = $RelativePath } } else { $t.rootTitle }
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add($title)
    $lines.Add('')
    $lines.Add($t.intro)
    $lines.Add('')
    $lines.Add((Expand-Template $t.dirRoleDefault @{ role = (Get-RoleFor $RelativePath) }))
    $lines.Add('')
    $lines.Add($t.hideDirs)
    $lines.Add('')

    $files = @(Get-ChildItem -File $Dir | Where-Object { $_.Name -ne 'code-map.md' } | Sort-Object Name)
    if ($RelativePath -and (Test-Path (Join-Path $Dir 'code-map.md'))) { }
    $sourceCount = @($files | Where-Object { $_.Extension -eq '.kt' }).Count
    if ($sourceCount -gt 0 -and $RelativePath -match '/kotlin/com/jiancai') {
        $package = ($RelativePath -replace '.*/kotlin/', '') -replace '/', '.'
        $lines.Add((Expand-Template $t.packageRole @{ package = $package; count = $sourceCount }))
        $lines.Add('')
    }

    $lines.Add($t.fileHeader)
    $lines.Add('')
    $lines.Add($t.fileTableHead)
    $lines.Add('| --- | --- |')
    $isModuleRoot = $RelativePath -match '^(shared|fpk-server|android-app|docs|fpk-server/[^/]+|android-app/[^/]+)$'
    if ($RelativePath -eq '' -or $isModuleRoot) {
        if (Test-Path (Join-Path $Dir 'module.md')) {
            $lines.Add('| `module.md` | ' + $t.moduleDoc + ' |')
        }
        $lines.Add('| `code-map.md` | ' + $t.codeMapSelf + ' |')
    }
    foreach ($file in $files) {
        if ($file.Name -eq 'module.md') { continue }
        $description = ''
        if ($file.Extension -eq '.kt') {
            $description = Get-FirstDocLine -Path $file.FullName
        } elseif ($file.Extension -eq '.kts') {
            $firstComment = @(Get-Content -Encoding UTF8 $file.FullName | Where-Object { $_.Trim().StartsWith('//') } | Select-Object -First 1)
            if ($firstComment.Count -gt 0) { $description = $firstComment[0].Trim().TrimStart('/').Trim() }
        }
        if (-not $description) { $description = $file.Name }
        $description = $description.Replace('|', '/')
        $lines.Add("| ``$($file.Name)`` | $description |")
    }

    $subDirs = @(Get-ChildItem -Directory $Dir | Where-Object { $excluded -notcontains $_.Name } | Sort-Object Name)
    if ($subDirs.Count -gt 0) {
        $lines.Add('')
        $lines.Add($t.subHeader)
        $lines.Add('')
        $lines.Add($t.subTableHead)
        $lines.Add('| --- | --- |')
        foreach ($sub in $subDirs) {
            $childRelative = if ($RelativePath) { "$RelativePath/$($sub.Name)" } else { $sub.Name }
            $lines.Add("| ``$($sub.Name)/`` | 见 ``$childRelative/code-map.md`` |")
        }
    }

    Set-Content -Path (Join-Path $Dir 'code-map.md') -Value $lines -Encoding utf8
    $script:mapCount++
}

function Invoke-Tree {
    param([string]$Dir, [string]$RelativePath)

    New-DirMap -Dir $Dir -RelativePath $RelativePath

    foreach ($file in @(Get-ChildItem -File $Dir | Where-Object { $_.Extension -eq '.kt' })) {
        Set-FileHeader -Path $file.FullName
    }

    foreach ($sub in @(Get-ChildItem -Directory $Dir | Where-Object { $excluded -notcontains $_.Name })) {
        $childRelative = if ($RelativePath) { "$RelativePath/$($sub.Name)" } else { $sub.Name }
        Invoke-Tree -Dir $sub.FullName -RelativePath $childRelative
    }
}

foreach ($root in $Roots) {
    $rootDir = Join-Path $RepoRoot $root
    if (-not (Test-Path $rootDir)) { Write-Warning "skip missing root: $root"; continue }
    Invoke-Tree -Dir $rootDir -RelativePath ($root -replace '\\', '/')
}

# repository root map (top-level files and directories)
New-DirMap -Dir $RepoRoot -RelativePath ''

Write-Host (Expand-Template $t.done @{ maps = $script:mapCount; headers = $script:headerCount })
