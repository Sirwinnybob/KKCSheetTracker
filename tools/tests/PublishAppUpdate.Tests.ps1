param([string]$Publisher = (Join-Path $PSScriptRoot '..\publish_app_update.ps1'))
$ErrorActionPreference = 'Stop'
function Assert-That([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('kkc-publish-test-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testRoot | Out-Null
$feed = Join-Path $testRoot '.appupdates\apps'
$legacy = Join-Path $testRoot '.Updates'
$package = 'com.example.first'
$appDir = Join-Path $feed $package
$otherDir = Join-Path $feed 'com.example.second'
New-Item -ItemType Directory -Path $appDir,$otherDir,$legacy | Out-Null
$source = Join-Path $testRoot 'source.apk'
Set-Content -LiteralPath $source -Value 'new signed artifact fixture'
Set-Content -LiteralPath (Join-Path $appDir 'old.apk') -Value 'old'
Set-Content -LiteralPath (Join-Path $otherDir 'other.apk') -Value 'other'
Set-Content -LiteralPath (Join-Path $legacy 'first-v1.apk') -Value 'old'
Set-Content -LiteralPath (Join-Path $legacy 'second-v1.apk') -Value 'other'
$arguments = @{
    SourceApk=$source; FeedRoot=$feed; PackageName=$package; VersionCode=2; VersionName='1.0.2'
    LegacyDirectory=$legacy; LegacyFileName='first-v2.apk'; LegacyPattern='first-v*.apk'
}
try {
    & $Publisher @arguments
    $target = Join-Path $appDir 'com.example.first-v1.0.2-2.apk'
    Assert-That (Test-Path -LiteralPath $target) 'New artifact missing'
    Assert-That (-not (Test-Path -LiteralPath (Join-Path $appDir 'old.apk'))) 'Old canonical APK was retained'
    Assert-That (-not (Test-Path -LiteralPath (Join-Path $legacy 'first-v1.apk'))) 'Old legacy APK was retained'
    Assert-That (Test-Path -LiteralPath (Join-Path $otherDir 'other.apk')) 'Another package was deleted'
    Assert-That (Test-Path -LiteralPath (Join-Path $legacy 'second-v1.apk')) 'Another legacy package was deleted'
    $manifest = Get-Content -LiteralPath (Join-Path $feed 'manifest.json') -Raw | ConvertFrom-Json
    $entry = @($manifest.apps | Where-Object packageName -eq $package)[0]
    Assert-That ($entry.versionCode -eq 2) 'Manifest version mismatch'
    Assert-That ($entry.sha256 -eq (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()) 'Manifest hash mismatch'
    Assert-That ((Get-FileHash -LiteralPath (Join-Path $legacy 'first-v2.apk')).Hash -eq (Get-FileHash -LiteralPath $target).Hash) 'Legacy artifact mismatch'

    # Missing sources and rejected downgrades must preserve the published version.
    $arguments.SourceApk = Join-Path $testRoot 'missing.apk'
    $failed = $false
    try { & $Publisher @arguments } catch { $failed = $true }
    Assert-That $failed 'Missing source was accepted'
    Assert-That (Test-Path -LiteralPath $target) 'Missing source deleted current version'
    $arguments.SourceApk = $source
    $arguments.VersionCode = 1
    $failed = $false
    try { & $Publisher @arguments } catch { $failed = $true }
    Assert-That $failed 'Downgrade was accepted'
    Assert-That (Test-Path -LiteralPath $target) 'Downgrade deleted current version'

    # Failure writing the bridge happens before changing the manifest or cleaning old files.
    $blocked = Join-Path $testRoot 'blocked'
    Set-Content -LiteralPath $blocked -Value 'not a directory'
    $arguments.LegacyDirectory = $blocked
    $arguments.VersionCode = 3
    $arguments.VersionName = '1.0.3'
    $failed = $false
    try { & $Publisher @arguments } catch { $failed = $true }
    Assert-That $failed 'Invalid bridge directory was accepted'
    Assert-That (Test-Path -LiteralPath $target) 'Failed bridge deleted published APK'
    $manifest = Get-Content -LiteralPath (Join-Path $feed 'manifest.json') -Raw | ConvertFrom-Json
    Assert-That ($manifest.apps[0].versionCode -eq 2) 'Failed bridge changed manifest'

    # A following successful release cleans the superseded file and its manifest history.
    $arguments.LegacyDirectory = $legacy
    $arguments.LegacyFileName = 'first-v3.apk'
    & $Publisher @arguments
    Assert-That (-not (Test-Path -LiteralPath $target)) 'Superseded release still exists'
    $manifest = Get-Content -LiteralPath (Join-Path $feed 'manifest.json') -Raw | ConvertFrom-Json
    Assert-That (@($manifest.history | Where-Object packageName -eq $package).Count -eq 0) 'History points at deleted APKs'
    Write-Output 'PASS: publication, hashes, package-scoped cleanup, source failure, downgrade, bridge failure, manifest history'
} finally {
    $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if (-not $resolvedTestRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test cleanup path' }
    Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
}

