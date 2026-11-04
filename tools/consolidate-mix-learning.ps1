<#
.SYNOPSIS
Consolidates mix learning singles and bundles after verifying a new atomic bundle.
.DESCRIPTION
Root is the learning directory itself. Only root JSON files and bundles/*.json
are inputs. WhatIf reports projected counts and never creates or changes files.
Newer versions stay in place; damaged inputs are kept in bad/. Do not run while
another process is deliberately rewriting existing learning files.
#>
[CmdletBinding(SupportsShouldProcess)]
param([string]$Root = 'Y:\Ready Jobs\.metadata\mix_order_learning')

function Get-MixLearningHash([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($Bytes)) } finally { $sha.Dispose() }
}

function Get-MixLearningSnapshot([string]$Path) {
    # A failed read (offline share, vanished file, writer holding a lock) is retried next run.
    try {
        $info = [IO.FileInfo]::new($Path)
        $modified = $info.LastWriteTimeUtc.Ticks
        $length = $info.Length
        $bytes = [IO.File]::ReadAllBytes($Path)
        $info.Refresh()
        if (-not $info.Exists -or $modified -ne $info.LastWriteTimeUtc.Ticks -or $length -ne $info.Length) { return $null }
        return [pscustomobject]@{
            Path = $Path; Modified = $modified; Length = $length
            Hash = (Get-MixLearningHash $bytes); Text = [Text.Encoding]::UTF8.GetString($bytes).TrimStart([char]0xfeff)
        }
    } catch { return $null }
}

function Test-MixLearningUnchanged($Snapshot) {
    $current = Get-MixLearningSnapshot $Snapshot.Path
    return ($null -ne $current -and $current.Modified -eq $Snapshot.Modified -and
        $current.Length -eq $Snapshot.Length -and $current.Hash -ceq $Snapshot.Hash)
}

function Get-MixLearningVersion($Value) {
    $property = $Value.PSObject.Properties['version']
    if ($null -eq $property -or $null -eq $property.Value) { return 1 }
    $number = 0
    if (-not [int]::TryParse([string]$property.Value, [ref]$number)) { throw 'Invalid version' }
    return $number
}

function Test-MixLearningRecord($Record) {
    if ($Record -isnot [pscustomobject]) { return $false }
    $id = $Record.PSObject.Properties['recordId']
    if ($null -eq $id -or [string]::IsNullOrWhiteSpace([string]$id.Value) -or
        $id.Value -is [array] -or $id.Value -is [pscustomobject]) { return $false }
    foreach ($name in @('defaultOrder', 'suggestedOrder', 'finalOrder', 'lockedSheets', 'weightsUsed')) {
        $property = $Record.PSObject.Properties[$name]
        if ($null -eq $property -or $property.Value -isnot [array]) { return $false }
        foreach ($element in $property.Value) {
            if ($element -is [array] -or $element -is [pscustomobject]) { return $false }
            if ($name -eq 'weightsUsed' -and -not (Test-MixLearningNumber $element)) { return $false }
        }
    }
    $sheets = $Record.PSObject.Properties['sheets']
    if ($null -eq $sheets -or $sheets.Value -isnot [pscustomobject]) { return $false }
    # Gson rejects malformed typed fields. Keeping one here would make the app
    # skip the entire output bundle, including otherwise valid records.
    foreach ($name in @('kind', 'job', 'material', 'mixFile', 'tabletId', 'appliedAt', 'appVersion', 'mode')) {
        $property = $Record.PSObject.Properties[$name]
        if ($null -ne $property -and ($property.Value -is [array] -or $property.Value -is [pscustomobject])) { return $false }
    }
    foreach ($name in @('edgeBandIn', 'smallPartIn')) {
        $property = $Record.PSObject.Properties[$name]
        if ($null -ne $property -and -not (Test-MixLearningNumber $property.Value)) { return $false }
    }
    foreach ($sheet in $sheets.Value.PSObject.Properties) {
        if ($null -eq $sheet.Value) { continue }
        if ($sheet.Value -isnot [pscustomobject]) { return $false }
        foreach ($name in @('smallEdge', 'smallInterior', 'smallEdgeArea', 'minDim', 'partCount')) {
            $property = $sheet.Value.PSObject.Properties[$name]
            if ($null -ne $property -and -not (Test-MixLearningNumber $property.Value)) { return $false }
        }
    }
    return $true
}

function Test-MixLearningNumber($Value) {
    if ($null -eq $Value) { return $true }
    $number = 0.0
    return [double]::TryParse([string]$Value, [Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture, [ref]$number)
}

# Windows PowerShell 5.1 has no -DateKind and keeps date-like strings as-is; pwsh 7.5+
# converts them to [DateTime] unless -DateKind String is given. Detected once, here.
$script:MixLearningJsonHasDateKind = (Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')
function ConvertFrom-MixLearningJson([string]$Text) {
    if ($script:MixLearningJsonHasDateKind) { return ConvertFrom-Json -InputObject $Text -DateKind String -ErrorAction Stop }
    return ConvertFrom-Json -InputObject $Text -ErrorAction Stop
}

function Read-MixLearningInput($Snapshot, [bool]$Bundle) {
    try {
        if (-not $Snapshot.Text.TrimStart().StartsWith('{')) { throw 'Expected object' }
        $value = ConvertFrom-MixLearningJson $Snapshot.Text
        if ($value -isnot [pscustomobject]) { throw 'Expected object' }
        if ((Get-MixLearningVersion $value) -gt 1) { return [pscustomobject]@{ State = 'Unknown' } }
        if (-not $Bundle) {
            if (-not (Test-MixLearningRecord $value)) { throw 'Unusable record' }
            return [pscustomobject]@{ State = 'Good'; Records = @($value); RetireToBad = $false; Snapshot = $Snapshot }
        }
        $property = $value.PSObject.Properties['records']
        if ($null -eq $property -or $property.Value -isnot [array]) { throw 'Expected records array' }
        $records = [Collections.Generic.List[object]]::new()
        $unusable = $false
        foreach ($record in $property.Value) {
            if ($null -eq $record) { continue }
            if ($record -isnot [pscustomobject]) { $unusable = $true; continue }
            # A future embedded record makes the entire original bundle untouchable.
            if ((Get-MixLearningVersion $record) -gt 1) { return [pscustomobject]@{ State = 'Unknown' } }
            if (Test-MixLearningRecord $record) { $records.Add($record) } else { $unusable = $true }
        }
        if ($unusable -and $records.Count -eq 0) { throw 'No usable records' }
        return [pscustomobject]@{ State = 'Good'; Records = @($records.ToArray()); RetireToBad = $unusable; Snapshot = $Snapshot }
    } catch { return [pscustomobject]@{ State = 'Bad'; Snapshot = $Snapshot } }
}

function Move-MixLearningToBad([string]$Root, $Snapshot) {
    if (-not (Test-MixLearningUnchanged $Snapshot)) { return $false }
    try {
        $dir = Join-Path $Root 'bad'
        [IO.Directory]::CreateDirectory($dir) | Out-Null
        $name = [IO.Path]::GetFileName($Snapshot.Path)
        $target = Join-Path $dir $name
        if ([IO.File]::Exists($target)) {
            $target = Join-Path $dir ([IO.Path]::GetFileNameWithoutExtension($name) + '-' + [guid]::NewGuid().ToString('N') + '.json')
        }
        # Same-volume rename, never copy-overwrite or delete an unverified input.
        if (-not (Test-MixLearningUnchanged $Snapshot)) { return $false }
        [IO.File]::Move($Snapshot.Path, $target)
        return $true
    } catch { return $false }
}

function Test-MixLearningBundle([string]$Path, $Ids) {
    try {
        $snapshot = Get-MixLearningSnapshot $Path
        if ($null -eq $snapshot) { return $false }
        $bundle = ConvertFrom-MixLearningJson $snapshot.Text
        if ($bundle.version -ne 1 -or $bundle.kind -cne 'bundle' -or $bundle.records -isnot [array]) { return $false }
        $present = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($record in $bundle.records) {
            if (-not (Test-MixLearningRecord $record)) { return $false }
            [void]$present.Add([string]$record.recordId)
        }
        foreach ($id in $Ids) { if (-not $present.Contains($id)) { return $false } }
        return $true
    } catch { return $false }
}

function Get-MixLearningBundlePath([string]$Root, [DateTimeOffset]$Timestamp) {
    do {
        $stamp = $Timestamp.ToString("yyyy-MM-dd'T'HH-mm-ss-fff", [Globalization.CultureInfo]::InvariantCulture)
        $path = Join-Path (Join-Path $Root 'bundles') ($stamp + '_pc_bundle.json')
        $Timestamp = $Timestamp.AddMilliseconds(1)
    } while ([IO.File]::Exists($path))
    return $path
}

function Invoke-MixLearningConsolidation {
    [CmdletBinding(SupportsShouldProcess)]
    param([Parameter(Mandatory)][string]$Root)
    $ErrorActionPreference = 'Stop'
    $Root = [IO.Path]::GetFullPath($Root)
    $good = [Collections.Generic.List[object]]::new()
    $bad = [Collections.Generic.List[object]]::new()
    $unknown = 0
    $moved = 0
    $absorbed = 0
    $bundlePath = 'none'
    $recordCount = 0
    if ([IO.Directory]::Exists($Root)) {
        # Freeze the complete list before parsing; later arrivals are never touched.
        $inputs = @()
        foreach ($location in @(@{ Path = $Root; Bundle = $false }, @{ Path = (Join-Path $Root 'bundles'); Bundle = $true })) {
            if (-not [IO.Directory]::Exists($location.Path)) { continue }
            $inputs += @(Get-ChildItem -LiteralPath $location.Path -File -Force | Where-Object { $_.Extension -ieq '.json' } |
                Sort-Object Name | ForEach-Object { [pscustomobject]@{ Path = $_.FullName; Bundle = $location.Bundle } })
        }
        foreach ($inputFile in $inputs) {
            $snapshot = Get-MixLearningSnapshot $inputFile.Path
            if ($null -eq $snapshot) { continue }
            $parsed = Read-MixLearningInput $snapshot $inputFile.Bundle
            switch ($parsed.State) {
                'Unknown' { $unknown++ }
                'Bad' { $bad.Add($parsed) }
                'Good' { $good.Add($parsed) }
            }
        }
        $partial = @($good | Where-Object { $_.RetireToBad }).Count
        $needsMerge = $good.Count -gt 1 -or ($good.Count -gt 0 -and ($bad.Count -gt 0 -or $partial -gt 0))
        $byId = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        $records = [Collections.Generic.List[object]]::new()
        if ($needsMerge) {
            foreach ($item in $good) {
                foreach ($record in $item.Records) {
                    $id = [string]$record.recordId
                    if (-not $byId.ContainsKey($id)) { $byId.Add($id, $record); $records.Add($record) }
                }
            }
            $recordCount = $records.Count
            $bundlePath = Get-MixLearningBundlePath $Root ([DateTimeOffset]::Now)
        }
        if (($needsMerge -or $bad.Count -gt 0) -and $PSCmdlet.ShouldProcess($Root, 'Consolidate mix learning data and quarantine unreadable inputs')) {
            foreach ($item in $bad) { if (Move-MixLearningToBad $Root $item.Snapshot) { $moved++ } }
            if ($needsMerge) {
                $tempPath = $bundlePath + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
                $verified = $false
                $published = $false
                try {
                    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($bundlePath)) | Out-Null
                    $model = [ordered]@{
                        version = 1; kind = 'bundle'
                        createdAt = [DateTimeOffset]::Now.ToString("yyyy-MM-dd'T'HH:mm:ss.fffzzz", [Globalization.CultureInfo]::InvariantCulture)
                        source = 'pc'; records = @($records.ToArray())
                    }
                    $json = ConvertTo-Json -InputObject $model -Depth 20
                    [IO.File]::WriteAllText($tempPath, $json, [Text.UTF8Encoding]::new($false))
                    while (-not $published) {
                        try {
                            [IO.File]::Move($tempPath, $bundlePath)
                            $published = $true
                        } catch [IO.IOException] {
                            # Another writer may publish after name selection. Never overwrite it.
                            if (-not [IO.File]::Exists($bundlePath)) { throw }
                            $bundlePath = Get-MixLearningBundlePath $Root ([DateTimeOffset]::Now)
                        }
                    }
                    $verified = Test-MixLearningBundle $bundlePath $byId.Keys
                    if (-not $verified) { throw 'Bundle verification failed; inputs retained' }
                } catch {
                    Write-Warning $_.Exception.Message
                } finally {
                    if ([IO.File]::Exists($tempPath)) { [IO.File]::Delete($tempPath) }
                    if ($published -and -not $verified -and [IO.File]::Exists($bundlePath)) { [IO.File]::Delete($bundlePath) }
                }
                if ($verified) {
                    foreach ($item in $good) {
                        if (-not (Test-MixLearningUnchanged $item.Snapshot)) { continue }
                        if ($item.RetireToBad) {
                            if (Move-MixLearningToBad $Root $item.Snapshot) { $moved++; $absorbed++ }
                        } else {
                            try { [IO.File]::Delete($item.Snapshot.Path); $absorbed++ } catch { Write-Warning $_.Exception.Message }
                        }
                    }
                } else { $bundlePath = 'none'; $recordCount = 0 }
            }
        } elseif ($needsMerge -or $bad.Count -gt 0) {
            # Includes WhatIf: all the counts are projected, no directories or temp files exist.
            $absorbed = $good.Count
            $moved = $bad.Count + $partial
        }
    }
    Write-Output ('Records: {0}  Files absorbed: {1}  Bad moved: {2}  Left (newer version): {3}  Bundle: {4}' -f $recordCount, $absorbed, $moved, $unknown, $bundlePath)
}

Invoke-MixLearningConsolidation -Root $Root
