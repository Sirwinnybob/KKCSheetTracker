param([string]$FixturePath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$scriptPath = Join-Path $PSScriptRoot '..\consolidate-mix-learning.ps1'
function Assert-That([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Write-Json($Path, $Value) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
    [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Value -Depth 20), [Text.UTF8Encoding]::new($false))
}
function New-Record([string]$Id) {
    return [ordered]@{
        version = 1; kind = 'record'; recordId = $Id; job = '720'; material = 'Maple'
        mixFile = 'MapleMix.mix'; tabletId = 'pc-test'; appliedAt = '2026-11-03T10:00:00.000-08:00'
        appVersion = '9.0.17'; mode = 'smart'; edgeBandIn = 3.0; smallPartIn = 6.0
        weightsUsed = @(1.0, 0.001, 0.0, 0.0, 0.0)
        defaultOrder = @('1|R1A.pgm', '2|R2A.pgm'); suggestedOrder = @('2|R2A.pgm', '1|R1A.pgm')
        finalOrder = @('2|R2A.pgm', '1|R1A.pgm'); lockedSheets = @()
        sheets = [ordered]@{
            '1|R1A.pgm' = @{ smallEdge = 1; smallInterior = 2; smallEdgeArea = 0.125; minDim = 3.0; partCount = 9 }
            '2|R2A.pgm' = @{ smallEdge = 4; smallInterior = 0; smallEdgeArea = 0.25; minDim = 2.0; partCount = 12 }
        }
    }
}
function Snapshot($Path) {
    return (@(Get-ChildItem -LiteralPath $Path -Recurse -Force | Sort-Object FullName | ForEach-Object {
        if ($_.PSIsContainer) { 'D|' + $_.FullName } else {
            'F|' + $_.FullName + '|' + $_.Length + '|' + $_.LastWriteTimeUtc.Ticks + '|' + (Get-FileHash -LiteralPath $_.FullName).Hash
        }
    }) -join "`n")
}
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('kkc-mix-consolidation-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($scratch) | Out-Null
try {
    # Dot-sourcing loads helpers for deterministic failure/race checks below. No live share is used.
    . $scriptPath -Root (Join-Path $scratch 'missing') -WhatIf
    Assert-That (-not [IO.Directory]::Exists((Join-Path $scratch 'missing'))) 'Missing root was created'
    $root = Join-Path $scratch 'primary'
    Write-Json (Join-Path $root '01.json') (New-Record 'pc-1')
    Write-Json (Join-Path $root '02.json') (New-Record 'pc-2')
    Write-Json (Join-Path $root '03.sync-conflict-test.json') (New-Record 'pc-3')
    Write-Json (Join-Path $root 'bundles\old.json') @{ version = 1; kind = 'bundle'; records = @((New-Record 'pc-2'), (New-Record 'pc-4')) }
    [IO.File]::WriteAllText((Join-Path $root 'garbage.json'), '{"version":1,')
    Write-Json (Join-Path $root 'future.json') @{ version = 2; anything = 'keep me' }
    [IO.File]::WriteAllText((Join-Path $root 'notes.txt'), 'ignore me')
    [IO.File]::WriteAllText((Join-Path $root 'inflight.json.tmp'), 'ignore me too')
    $before = Snapshot $root
    $dry = & $scriptPath -Root $root -WhatIf
    Assert-That ($before -ceq (Snapshot $root)) 'WhatIf changed files or directories'
    Assert-That (($dry -join '') -match 'Records: 4  Files absorbed: 4  Bad moved: 1  Left \(newer version\): 1') 'Dry run did not print projected counts'
    $real = & $scriptPath -Root $root
    Assert-That (($real -join '') -match 'Records: 4  Files absorbed: 4  Bad moved: 1  Left \(newer version\): 1') 'Real run counts differ'
    $bundle = @(Get-ChildItem -LiteralPath (Join-Path $root 'bundles') -File)
    Assert-That ($bundle.Count -eq 1) 'Expected exactly one bundle'
    Assert-That ($bundle[0].Name -cmatch '^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-\d{3}_pc_bundle\.json$') 'Bundle filename does not follow timestamp/source contract'
    $bytes = [IO.File]::ReadAllBytes($bundle[0].FullName)
    Assert-That (-not ($bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191)) 'Bundle has UTF8 BOM'
    $json = [IO.File]::ReadAllText($bundle[0].FullName) | ConvertFrom-Json
    Assert-That ($json.version -eq 1 -and $json.kind -ceq 'bundle' -and $json.source -ceq 'pc') 'Wrong envelope'
    Assert-That (($json.records.recordId | Sort-Object) -join ',' -ceq 'pc-1,pc-2,pc-3,pc-4') 'Wrong deduped record IDs'
    Assert-That ($json.records[0].sheets.'1|R1A.pgm'.smallInterior -eq 2) 'Feature map changed'
    Assert-That ([IO.File]::Exists((Join-Path $root 'bad\garbage.json'))) 'Garbage was not quarantined'
    Assert-That ([IO.File]::Exists((Join-Path $root 'future.json'))) 'Newer version was removed'
    Assert-That (@(Get-ChildItem -LiteralPath $root -File).Count -eq 3) 'Singles survived or ignored files were removed'
    $after = Snapshot $root
    $again = & $scriptPath -Root $root
    Assert-That ($after -ceq (Snapshot $root)) 'Second run was not idempotent'
    Assert-That (($again -join '') -match 'Files absorbed: 0.*Bundle: none') 'Second run claimed another bundle'
    if ($FixturePath) { [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($FixturePath)) | Out-Null; [IO.File]::Copy($bundle[0].FullName, $FixturePath, $true) }

    $mixed = Join-Path $scratch 'mixed'
    Write-Json (Join-Path $mixed 'bundles\mixed.json') @{ version = 1; records = @((New-Record 'salvaged'), @{}, 7, $null) }
    Write-Json (Join-Path $mixed 'bundles\future-inside.json') @{ version = 1; records = @((New-Record 'old'), @{ version = 2; recordId = 'future' }) }
    Write-Json (Join-Path $mixed 'future-single.json') @{ version = 2 }
    [IO.Directory]::CreateDirectory((Join-Path $mixed 'bad')) | Out-Null
    [IO.File]::WriteAllText((Join-Path $mixed 'bad\mixed.json'), 'earlier bad file')
    $result = & $scriptPath -Root $mixed
    Assert-That (($result -join '') -match 'Records: 1  Files absorbed: 1  Bad moved: 1  Left \(newer version\): 2') 'Partial/newer bundle handling differs'
    Assert-That ([IO.File]::ReadAllText((Join-Path $mixed 'bad\mixed.json')) -ceq 'earlier bad file') 'Quarantine overwrote an old file'
    Assert-That (@(Get-ChildItem -LiteralPath (Join-Path $mixed 'bad') -File).Count -eq 2) 'Partial bundle original was lost'

    $malformed = Join-Path $scratch 'malformed-fields'
    Write-Json (Join-Path $malformed 'good.json') (New-Record 'usable')
    $invalid = New-Record 'bad-weights'
    $invalid.weightsUsed = @('not-a-number')
    Write-Json (Join-Path $malformed 'weights.json') $invalid
    $invalid = New-Record 'bad-features'
    $invalid.sheets.'1|R1A.pgm'.smallEdge = @{ wrong = 'shape' }
    Write-Json (Join-Path $malformed 'features.json') $invalid
    $invalid = New-Record 'bad-mode'
    $invalid.mode = @{ wrong = 'shape' }
    Write-Json (Join-Path $malformed 'mode.json') $invalid
    $result = & $scriptPath -Root $malformed
    Assert-That (($result -join '') -match 'Records: 1  Files absorbed: 1  Bad moved: 3') 'Malformed fields would poison the Kotlin bundle reader'

    $caseRoot = Join-Path $scratch 'case'
    Write-Json (Join-Path $caseRoot 'a.json') (New-Record 'ID')
    Write-Json (Join-Path $caseRoot 'b.json') (New-Record 'id')
    $result = & $scriptPath -Root $caseRoot
    Assert-That (($result -join '') -match 'Records: 2') 'Record IDs must use case-sensitive dedupe'

    $single = Join-Path $scratch 'singleton'
    Write-Json (Join-Path $single 'one.json') (New-Record 'alone')
    $before = Snapshot $single
    $null = & $scriptPath -Root $single
    Assert-That ($before -ceq (Snapshot $single)) 'Singleton caused a write'

    $realVerify = ${function:Test-MixLearningBundle}
    $failed = Join-Path $scratch 'verify-failure'
    Write-Json (Join-Path $failed 'one.json') (New-Record 'v1')
    Write-Json (Join-Path $failed 'two.json') (New-Record 'v2')
    $before = Snapshot $failed
    function Test-MixLearningBundle { return $false }
    $null = Invoke-MixLearningConsolidation -Root $failed -WarningAction SilentlyContinue
    Assert-That ([IO.File]::Exists((Join-Path $failed 'one.json')) -and [IO.File]::Exists((Join-Path $failed 'two.json'))) 'Failed verification deleted inputs'
    Assert-That (@(Get-ChildItem -LiteralPath $failed -Recurse -File).Count -eq 2) 'Failed verification left output files'
    Set-Item Function:Test-MixLearningBundle -Value $realVerify

    $collision = Join-Path $scratch 'filename-collision'
    $fixedStamp = [DateTimeOffset]::Parse('2026-11-03T10:00:00.000-08:00')
    $firstPath = Get-MixLearningBundlePath $collision $fixedStamp
    Write-Json $firstPath @{ foreign = 'keep me' }
    $secondPath = Get-MixLearningBundlePath $collision $fixedStamp
    Assert-That ([IO.Path]::GetFileName($secondPath) -ceq '2026-11-03T10-00-00-001_pc_bundle.json') 'Collision did not increment the timestamp'

    $foreignRoot = Join-Path $scratch 'foreign-output'
    Write-Json (Join-Path $foreignRoot 'one.json') (New-Record 'f1')
    Write-Json (Join-Path $foreignRoot 'two.json') (New-Record 'f2')
    $realBundlePath = ${function:Get-MixLearningBundlePath}
    $script:chooseCalls = 0
    function Get-MixLearningBundlePath($Root, $Timestamp) {
        $script:chooseCalls++
        if ($script:chooseCalls -gt 1) { throw 'Simulated failure selecting another name after concurrent publication' }
        $path = Join-Path (Join-Path $Root 'bundles') '2026-11-03T10-00-00-000_pc_bundle.json'
        Write-Json $path @{ foreign = 'concurrent writer' }
        return $path
    }
    $null = Invoke-MixLearningConsolidation -Root $foreignRoot -WarningAction SilentlyContinue
    Assert-That ([IO.File]::Exists((Join-Path $foreignRoot 'one.json')) -and [IO.File]::Exists((Join-Path $foreignRoot 'two.json'))) 'Failed atomic rename deleted inputs'
    $foreignPath = Join-Path $foreignRoot 'bundles\2026-11-03T10-00-00-000_pc_bundle.json'
    Assert-That (([IO.File]::ReadAllText($foreignPath) | ConvertFrom-Json).foreign -ceq 'concurrent writer') 'Failed publication deleted someone else''s bundle'
    Assert-That (@(Get-ChildItem -LiteralPath (Join-Path $foreignRoot 'bundles') -File).Count -eq 1) 'Failed publication left temp files'
    Set-Item Function:Get-MixLearningBundlePath -Value $realBundlePath

    $race = Join-Path $scratch 'changed-input'
    Write-Json (Join-Path $race 'one.json') (New-Record 'before')
    Write-Json (Join-Path $race 'two.json') (New-Record 'other')
    $changed = Join-Path $race 'one.json'
    $oldTime = [IO.File]::GetLastWriteTimeUtc($changed)
    function Test-MixLearningBundle($Path, $Ids) {
        # Same length and timestamp: a metadata-only check would incorrectly delete this rewrite.
        $text = [IO.File]::ReadAllText($changed).Replace('before', 'after!')
        [IO.File]::WriteAllText($changed, $text, [Text.UTF8Encoding]::new($false))
        [IO.File]::SetLastWriteTimeUtc($changed, $oldTime)
        Write-Json (Join-Path $race 'late.json') (New-Record 'late')
        return (& $realVerify $Path $Ids)
    }
    $null = Invoke-MixLearningConsolidation -Root $race
    Assert-That ([IO.File]::Exists($changed)) 'Changed input was deleted'
    Assert-That ([IO.File]::Exists((Join-Path $race 'late.json'))) 'Late arrival was deleted'
    Assert-That (-not [IO.File]::Exists((Join-Path $race 'two.json'))) 'Unchanged input was not absorbed'
    Set-Item Function:Test-MixLearningBundle -Value $realVerify

    Write-Output ('PASS: consolidation dry/real/idempotent, quarantine, newer versions, case-sensitive dedupe, verification failure, changed inputs, late arrivals; PowerShell ' + $PSVersionTable.PSVersion)
} finally {
    # Only the verified, uniquely named temp tree created by this harness is removed.
    $resolvedScratch = [IO.Path]::GetFullPath($scratch)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolvedScratch.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolvedScratch) -notlike 'kkc-mix-consolidation-*') { throw 'Unsafe scratch cleanup path' }
    Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
}
