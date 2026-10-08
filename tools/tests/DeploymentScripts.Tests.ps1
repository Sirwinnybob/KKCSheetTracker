param(
    [string[]]$ReleaseScripts = @(
        (Join-Path $PSScriptRoot '..\..\deploy_update.ps1'),
        'C:\Scripts\Hours Tracker\AndroidApp\deploy_release.ps1',
        'C:\Scripts\Assimp\AssimpAndroid\deploy_update.ps1'
    ),
    [string]$AssimpDebugScript = 'C:\Scripts\Assimp\AssimpAndroid\deploy_debug.ps1'
)
$ErrorActionPreference = 'Stop'
function Assert-That([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
$root = Join-Path ([System.IO.Path]::GetTempPath()) ('kkc-deploy-test-' + [guid]::NewGuid())
$feed = Join-Path $root '.appupdates\apps'
$releaseRoot = Join-Path $root 'app\build\outputs\apk\release'
New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
$source = Join-Path $releaseRoot 'app-release.apk'
Set-Content -LiteralPath $source -Value 'release fixture'
$packages = @('com.kkc.sheettracker','com.example.timecard','com.anandmuralidhar.assimpandroid')
try {
    for ($i=0; $i -lt $ReleaseScripts.Count; $i++) {
        $build = "android { defaultConfig { versionCode = 3; versionName = `"1.0.3`" } }"
        Set-Content -LiteralPath (Join-Path $root 'app\build.gradle.kts') -Value $build
        $metadata = @{
            applicationId=$packages[$i]; variantName='release'
            elements=@(@{versionCode=3; versionName='1.0.3'; outputFile='app-release.apk'})
        }
        $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $releaseRoot 'output-metadata.json')
        & $ReleaseScripts[$i] -ProjectPath $root -FeedRoot $feed -SkipBuild
        Assert-That (Test-Path -LiteralPath (Join-Path $feed "$($packages[$i])\$($packages[$i])-v1.0.3-3.apk")) 'Wrapper did not publish the expected package'
    }
    $manifestPath = Join-Path $feed 'manifest.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    Assert-That ($manifest.apps.Count -eq 3) 'Publishing another app lost a manifest entry'
    $before = (Get-FileHash -LiteralPath $manifestPath).Hash
    Set-Content -LiteralPath (Join-Path $root 'app\build.gradle.kts') -Value 'versionCode = 4; versionName = "1.0.4"'
    $failed = $false
    try { & $ReleaseScripts[2] -ProjectPath $root -FeedRoot $feed -SkipBuild } catch { $failed=$true }
    Assert-That $failed 'Stale built APK was published'
    Assert-That ((Get-FileHash -LiteralPath $manifestPath).Hash -eq $before) 'Metadata failure changed manifest'

    $debugRoot = Join-Path $root 'app\build\outputs\apk\debug'
    New-Item -ItemType Directory -Path $debugRoot -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $debugRoot 'app-debug.apk') -Value 'debug fixture'
    Set-Content -LiteralPath (Join-Path $root 'app\build.gradle') -Value 'versionCode 4'
    $testing = Join-Path $root '.Testing_Updates'
    New-Item -ItemType Directory -Path $testing | Out-Null
    Set-Content -LiteralPath (Join-Path $testing 'assimp-v3.apk') -Value 'old'
    Set-Content -LiteralPath (Join-Path $testing 'other-v3.apk') -Value 'other'
    & $AssimpDebugScript -ProjectPath $root -UpdateDirectory $testing -SkipBuild
    Assert-That (-not (Test-Path -LiteralPath (Join-Path $testing 'assimp-v3.apk'))) 'Old debug version survived'
    Assert-That (Test-Path -LiteralPath (Join-Path $testing 'other-v3.apk')) 'Another debug app was deleted'
    Remove-Item -LiteralPath (Join-Path $debugRoot 'app-debug.apk')
    $failed=$false
    try { & $AssimpDebugScript -ProjectPath $root -UpdateDirectory $testing -SkipBuild } catch { $failed=$true }
    Assert-That $failed 'Missing debug source was accepted'
    Assert-That (Test-Path -LiteralPath (Join-Path $testing 'assimp-v4-debug.apk')) 'Failed debug publication removed current version'
    Write-Output 'PASS: all release wrappers, combined manifest, stale build rejection, debug cleanup and failure preservation'
} finally {
    $resolved = [System.IO.Path]::GetFullPath($root)
    if (-not $resolved.StartsWith([System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

