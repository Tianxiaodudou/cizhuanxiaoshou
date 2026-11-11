# Writes the per-file code-map header required by the task.
#
# Header schema (low-churn fields only, volatile details live in docs/CODEMAPS/):
#   @file        repository-relative path
#   @role        one-line responsibility (first KDoc line)
#   @exports     public top-level declarations
#   @depends     directly imported internal modules
#   @calledBy    known main callers (up to 3, source files only)
#   @sideEffects DB writes / reads, file IO (pure logging is not listed)
#
# ASCII-only script: PowerShell 5.1 reads no-BOM UTF-8 as ANSI, so all Chinese
# text lives in code-map-template.json (loaded with -Encoding UTF8).
# Re-running is idempotent: an existing generated header is replaced.
[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string[]]$Roots = @('shared', 'fpk-server', 'android-app')
)

$ErrorActionPreference = 'Stop'
$t = Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot 'code-map-template.json') | ConvertFrom-Json

$excluded = @('build', '.gradle', '.git', '.tools', '.kotlin', 'node_modules')
$script:headerCount = 0

# package prefix -> module (short name) so @depends stays readable
$moduleByPackage = @{
    'shared'            = 'shared'
    'db'                = 'db-core'
    'serial'            = 'serial'
    'config'            = 'config'
    'auth'              = 'auth'
    'account'           = 'account'
    'customer'          = 'customer'
    'category'          = 'category'
    'pricing'           = 'pricing'
    'order'             = 'order'
    'returncheck'       = 'return-check'
    'note'              = 'note'
    'settlement'        = 'settlement'
    'print'             = 'print'
    'observability'     = 'observability'
    'gateway'           = 'api-gateway'
    'app'               = 'app'
    'network'           = 'core-network'
    'session'           = 'core-session'
    'ui'                = 'core-ui'
    'shell'             = 'app-shell'
    'login'             = 'feature-login'
    'home'              = 'feature-home'
    'orderentry'        = 'feature-order-entry'
    'query'             = 'feature-query'
    'today'             = 'feature-today'
    'settle'            = 'feature-settle'
    'admin'             = 'feature-admin'
    'settings'          = 'feature-settings'
}

$androidModuleByPackage = @{
    'network'     = 'core-network'
    'session'     = 'core-session'
    'ui'          = 'core-ui'
    'shell'       = 'app-shell'
    'login'       = 'feature-login'
    'home'        = 'feature-home'
    'orderentry'  = 'feature-order-entry'
    'query'       = 'feature-query'
    'today'       = 'feature-today'
    'note'        = 'feature-note'
    'settle'      = 'feature-settle'
    'admin'       = 'feature-admin'
    'settings'    = 'feature-settings'
}

function Get-RelativePath([string]$Path) {
    return $Path.Replace($RepoRoot + '\', '').Replace('\', '/')
}

function Get-DeclRole {
    param([string[]]$Lines, [string]$Preferred)

    $declarations = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        $line = $Lines[$i]
        if (-not $line -or $line.StartsWith(' ') -or $line.StartsWith([char]9)) { continue }
        $trim = $line.Trim()
        if ($trim.StartsWith('*') -or $trim.StartsWith('//')) { continue }
        if (-not ($trim -match '^(?:(?:public|open|final|abstract|sealed|data|value|enum|annotation|expect|actual|fun|internal)\s+)*(class|object|interface|typealias|fun)\s')) { continue }
        $nameMatch = [regex]::Match($trim, '(?:class|object|interface|typealias|fun)\s+(?:<[^>]*>\s*)?(\w+)')
        $name = if ($nameMatch.Success) { $nameMatch.Groups[1].Value } else { '' }
        $declarations.Add([pscustomobject]@{ Index = $i; Name = $name })
    }
    if ($declarations.Count -eq 0) { return '' }

    # single-line KDoc: /** ... */
    $from = [Math]::Max(0, $declarations[0].Index - 10)
    for ($i = $declarations[0].Index - 1; $i -ge $from; $i--) {
        $oneLine = [regex]::Match($Lines[$i], '/\*\*(.*?)\*/')
        if ($oneLine.Success) {
            $text = $oneLine.Groups[1].Value.Trim()
            if ($text -and -not $text.StartsWith('@')) { return $text }
            break
        }
    }

    $target = $declarations[0]
    if ($Preferred) {
        foreach ($decl in $declarations) {
            if ($decl.Name -eq $Preferred) { $target = $decl; break }
        }
    }

    $docEnd = -1
    for ($i = $target.Index - 1; $i -ge [Math]::Max(0, $target.Index - 40); $i--) {
        if ($Lines[$i].Trim() -eq '*/') { $docEnd = $i; break }
    }
    if ($docEnd -lt 0) { return '' }

    $result = ''
    for ($i = $docEnd - 1; $i -ge 0; $i--) {
        $trim = $Lines[$i].Trim()
        if ($trim.StartsWith('/**')) { break }
        $text = $trim.TrimStart('*').Trim()
        if ($text -and -not $text.StartsWith('@')) { $result = $text }
    }
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

function Get-ExportedNames {
    param([string[]]$Lines)
    $modifiers = 'public|open|final|abstract|sealed|data|value|enum|annotation|expect|actual|override|suspend|inline|operator|infix|tailrec|external|fun|const'
    $pattern = '^(?:(?:' + $modifiers + ')\s+)*(fun|class|object|interface|typealias)\s+'
    $names = New-Object System.Collections.Generic.List[string]

    foreach ($line in $Lines) {
        if (-not $line -or $line.StartsWith(' ') -or $line.StartsWith([char]9)) { continue }
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith('*') -or $trimmed.StartsWith('//') -or $trimmed.StartsWith('/*')) { continue }
        if ($trimmed -match '^(private|internal)\b') { continue }
        if (-not ($trimmed -match $pattern)) { continue }

        $kind = $Matches[1]
        $rest = $trimmed.Substring($trimmed.IndexOf($kind) + $kind.Length).Trim()
        $m = [regex]::Match($rest, '^(?:<[^>]*>\s*)?(?:[\w\.]+\s*\.\s*)?(\w+)')
        if ($m.Success) {
            $name = $m.Groups[1].Value
            if ($names -notcontains $name) { $names.Add($name) }
        }
    }
    return $names
}

function Get-TypeNames {
    param([string[]]$Lines)
    $modifiers = 'public|open|final|abstract|sealed|data|value|enum|annotation|expect|actual|internal'
    $pattern = '^(?:(?:' + $modifiers + ')\s+)*(class|object|interface)\s+'
    $names = New-Object System.Collections.Generic.List[string]
    foreach ($line in $Lines) {
        if (-not $line -or $line.StartsWith(' ') -or $line.StartsWith([char]9)) { continue }
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith('*') -or $trimmed.StartsWith('//') -or $trimmed.StartsWith('/*')) { continue }
        if ($trimmed -match '^private\b') { continue }
        if (-not ($trimmed -match $pattern)) { continue }
        $kind = $Matches[1]
        $rest = $trimmed.Substring($trimmed.IndexOf($kind) + $kind.Length).Trim()
        $m = [regex]::Match($rest, '^(\w+)')
        if ($m.Success) {
            $name = $m.Groups[1].Value
            if ($name.Length -ge 5 -and $names -notcontains $name) { $names.Add($name) }
        }
    }
    return $names
}

function Get-DependsModules {
    param([string[]]$Lines, [string]$OwnModule)
    $modules = New-Object System.Collections.Generic.List[string]
    $join = "`n"
    $text = $Lines -join $join
    foreach ($m in [regex]::Matches($text, '(?m)^\s*import\s+com\.jiancai\.android\.(\w+)\.')) {
        $key = $m.Groups[1].Value
        if ($androidModuleByPackage.ContainsKey($key)) {
            $name = $androidModuleByPackage[$key]
            if ($name -ne $OwnModule -and $modules -notcontains $name) { $modules.Add($name) }
        }
    }
    foreach ($m in [regex]::Matches($text, '(?m)^\s*import\s+com\.jiancai\.(\w+)\.')) {
        $key = $m.Groups[1].Value
        if ($moduleByPackage.ContainsKey($key)) {
            $name = $moduleByPackage[$key]
            if ($name -ne $OwnModule -and $modules -notcontains $name) { $modules.Add($name) }
        }
    }
    return $modules
}

function Get-SideEffects {
    param([string[]]$Lines)
    $text = $Lines -join "`n"
    $hasSql = $text -match 'prepareStatement|createStatement|tx\.connection'

    if (-not $hasSql) {
        if ($text -match 'repository\.|Repository\(') { return $t.fhRepository }
        if ($text -match 'ApiClient|client\.call\(') { return $t.fhNetwork }
        if ($text -match 'txRunner|TxContext') { return $t.fhTx }
        if ($text -match 'File\(|readText\(|readLines\(') { return $t.fhFileIo }
        return $t.fhNoEffects
    }

    $tables = New-Object System.Collections.Generic.List[string]
    $isWrite = $false

    foreach ($m in [regex]::Matches($text, '(?i)INSERT\s+INTO\s+(\w+)')) {
        $isWrite = $true; if ($tables -notcontains $m.Groups[1].Value) { $tables.Add($m.Groups[1].Value) }
    }
    foreach ($m in [regex]::Matches($text, '(?i)UPDATE\s+(\w+)\s+SET')) {
        $isWrite = $true; if ($tables -notcontains $m.Groups[1].Value) { $tables.Add($m.Groups[1].Value) }
    }
    foreach ($m in [regex]::Matches($text, '(?i)DELETE\s+FROM\s+(\w+)')) {
        $isWrite = $true; if ($tables -notcontains $m.Groups[1].Value) { $tables.Add($m.Groups[1].Value) }
    }
    foreach ($m in [regex]::Matches($text, '(?i)CREATE\s+TABLE\s+(\w+)')) {
        $isWrite = $true; if ($tables -notcontains $m.Groups[1].Value) { $tables.Add($m.Groups[1].Value) }
    }
    if ($isWrite) { return $t.fhDbWrite.Replace('{tables}', ($tables -join ', ')) }

    foreach ($m in [regex]::Matches($text, '(?i)\bFROM\s+(\w+)')) {
        if ($tables -notcontains $m.Groups[1].Value) { $tables.Add($m.Groups[1].Value) }
    }
    if ($tables.Count -gt 0) { return $t.fhDbRead.Replace('{tables}', ($tables -join ', ')) }
    return $t.fhNoEffects
}

function Remove-OldHeader {
    param([string[]]$Lines)
    $result = $Lines
    while ($result.Count -ge 2 -and $result[0].Trim() -eq '/*') {
        $end = -1
        for ($i = 1; $i -lt [Math]::Min($result.Count, 40); $i++) {
            if ($result[$i].Trim() -eq '*/') { $end = $i; break }
        }
        if ($end -lt 0) { break }
        $inner = ($result[1..$end] -join "`n")
        $isOurs = $inner.Contains($t.fhMarker) -or $inner.Contains($t.oldMarker)
        if (-not $isOurs) { break }
        $next = $end + 1
        if ($next -lt $result.Count -and $result[$next].Trim() -eq '') { $next++ }
        $result = @($result[$next..($result.Count - 1)])
    }
    return $result
}

# ---- pass 1: collect files, exports and their text (for @calledBy) ----
$files = New-Object System.Collections.Generic.List[object]
foreach ($root in $Roots) {
    $rootDir = Join-Path $RepoRoot $root
    if (-not (Test-Path $rootDir)) { continue }
    foreach ($file in @(Get-ChildItem -Recurse -File -Filter *.kt $rootDir)) {
        $parts = $file.FullName.Split('\')
        $skip = $false
        foreach ($part in $parts) { if ($excluded -contains $part) { $skip = $true } }
        if ($skip) { continue }

        $lines = @(Get-Content -Encoding UTF8 $file.FullName)
        $body = @(Remove-OldHeader -Lines $lines)
        $codeLines = @($body | Where-Object {
            $trim = $_.Trim()
            -not ($trim.StartsWith('*') -or $trim.StartsWith('//') -or $trim.StartsWith('/*') -or $trim.StartsWith('*/'))
        })
        $files.Add([pscustomobject]@{
            Path     = $file.FullName
            Relative = Get-RelativePath $file.FullName
            Lines    = $body
            Text     = ($codeLines -join "`n")
            Exports  = @(Get-ExportedNames -Lines $body)
            Types    = @(Get-TypeNames -Lines $body)
        })
    }
}

# index: exported type name -> owning file (functions are excluded: too ambiguous)
$ownerIndex = @{}
foreach ($entry in $files) {
    foreach ($name in $entry.Types) {
        if (-not $ownerIndex.ContainsKey($name)) { $ownerIndex[$name] = $entry.Relative }
    }
}

function Test-NameInText {
    param([string]$Text, [string]$Name)
    $index = $Text.IndexOf($Name, [System.StringComparison]::Ordinal)
    while ($index -ge 0) {
        $beforeOk = $true
        $afterOk = $true
        if ($index -gt 0) {
            $c = $Text[$index - 1]
            if ([char]::IsLetterOrDigit($c) -or $c -eq '_') { $beforeOk = $false }
        }
        $end = $index + $Name.Length
        if ($end -lt $Text.Length) {
            $c = $Text[$end]
            if ([char]::IsLetterOrDigit($c) -or $c -eq '_') { $afterOk = $false }
        }
        if ($beforeOk -and $afterOk) { return $true }
        $index = $Text.IndexOf($Name, $index + 1, [System.StringComparison]::Ordinal)
    }
    return $false
}

# callers: owner file -> files that mention one of its exported types
$callersByOwner = @{}
foreach ($entry in $files) {
    foreach ($name in $ownerIndex.Keys) {
        $owner = $ownerIndex[$name]
        if ($owner -eq $entry.Relative) { continue }
        if (Test-NameInText -Text $entry.Text -Name $name) {
            if (-not $callersByOwner.ContainsKey($owner)) {
                $callersByOwner[$owner] = New-Object System.Collections.Generic.List[string]
            }
            $list = $callersByOwner[$owner]
            if ($list -notcontains $entry.Relative) { $list.Add($entry.Relative) }
        }
    }
}

# ---- pass 2: write headers ----
foreach ($entry in $files) {
    $ownModule = $entry.Relative.Split('/')[1]
    if ($entry.Relative.StartsWith('shared/')) { $ownModule = 'shared' }
    if ($entry.Relative.StartsWith('android-app/')) { $ownModule = $entry.Relative.Split('/')[1] }

    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($entry.Relative)
    $role = Get-DeclRole -Lines $entry.Lines -Preferred $baseName
    if (-not $role) { $role = Get-FirstDocLine -Path $entry.Path }
    if (-not $role) { $role = $t.fhNoDoc }
    $role = $role.TrimEnd(':', [char]0xFF1A, ' ')
    if ($role.Length -gt 90) { $role = $role.Substring(0, 90) + '...' }
    $role = $role.Replace('|', '/')

    $exportNames = @($entry.Exports)
    $exports = if ($exportNames.Count -gt 0) { ($exportNames -join ', ') } else { $t.fhNone }
    $depends = Get-DependsModules -Lines $entry.Lines -OwnModule $ownModule
    if ($depends.Count -eq 0) { $depends = @(); $dependsText = $t.fhNone } else { $dependsText = ($depends -join ', ') }

    $callers = New-Object System.Collections.Generic.List[string]
    if ($callersByOwner.ContainsKey($entry.Relative)) {
        foreach ($candidate in $callersByOwner[$entry.Relative]) {
            if ($candidate.Contains('/src/test/')) { continue }
            if ($callers -notcontains $candidate) { $callers.Add($candidate) }
        }
    }
    $callersText = if ($callers.Count -gt 0) { (($callers | Select-Object -First 3) -join ', ') } else { $t.fhNone }

    $effects = Get-SideEffects -Lines $entry.Lines

    $out = New-Object System.Collections.Generic.List[string]
    $out.Add('/*')
    $out.Add($t.fhFile.Replace('{path}', $entry.Relative))
    $out.Add($t.fhRole.Replace('{role}', $role))
    $out.Add($t.fhExports.Replace('{names}', $exports))
    $out.Add($t.fhDepends.Replace('{modules}', $dependsText))
    $out.Add($t.fhCalledBy.Replace('{files}', $callersText))
    $out.Add($t.fhSideEffects.Replace('{effects}', $effects))
    $out.Add(' */')
    $out.Add('')
    foreach ($line in $entry.Lines) { $out.Add($line) }

    Set-Content -Path $entry.Path -Value $out -Encoding utf8
    $script:headerCount++
}

Write-Host ($t.headerDone.Replace('{headers}', $script:headerCount))
