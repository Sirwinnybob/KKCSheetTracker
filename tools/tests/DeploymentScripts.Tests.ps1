param(
    [string[]]$ReleaseScripts = @(
        (Join-Path $PSScriptRoot '..\..\deploy_update.ps1'),
        'C:\Scripts\Hours Tracker\AndroidApp\deploy_release.ps1',
        'C:\Scripts\Assimp\AssimpAndroid\deploy_update.ps1',
        'C:\Scripts\VNCCast\deploy-android.ps1'
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
$legacy = Join-Path $root '.Updates'
New-Item -ItemType Directory -Path $legacy -Force | Out-Null
Set-Content -LiteralPath (Join-Path $legacy 'vnccast-v1.3-4-release.apk') -Value 'old VNC Cast'
Set-Content -LiteralPath (Join-Path $legacy 'unrelated.apk') -Value 'another app'
$packages = @('com.kkc.sheettracker','com.example.timecard','com.anandmuralidhar.assimpandroid','com.kkc.vnccast')
try {
    for ($i=0; $i -lt $ReleaseScripts.Count; $i++) {
        $build = "android { defaultConfig { versionCode = 3; versionName = `"1.0.3`" } }"
        Set-Content -LiteralPath (Join-Path $root 'app\build.gradle.kts') -Value $build
        $metadata = @{
            applicationId=$packages[$i]; variantName='release'
            elements=@(@{versionCode=3; versionName='1.0.3'; outputFile='app-release.apk'})
        }
        $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $releaseRoot 'output-metadata.json')
        $arguments = @{ProjectPath=$root; FeedRoot=$feed; SkipBuild=$true}
        if ($packages[$i] -eq 'com.kkc.vnccast') { $arguments.DistDirectory = Join-Path $root 'dist' }
        & $ReleaseScripts[$i] @arguments
        Assert-That (Test-Path -LiteralPath (Join-Path $feed "$($packages[$i])\$($packages[$i])-v1.0.3-3.apk")) 'Wrapper did not publish the expected package'
    }
    $manifestPath = Join-Path $feed 'manifest.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    Assert-That ($manifest.apps.Count -eq $ReleaseScripts.Count) 'Publishing another app lost a manifest entry'
    Assert-That ((Get-FileHash -LiteralPath (Join-Path $root 'dist\VNCCast.apk')).Hash -eq (Get-FileHash -LiteralPath $source).Hash) 'VNC Cast local distribution copy does not match the published APK'
    Assert-That (Test-Path -LiteralPath (Join-Path $legacy 'vnccast-v1.3-4-release.apk')) 'Release publishing removed an existing legacy APK'
    Assert-That ((Get-Content -LiteralPath (Join-Path $legacy 'vnccast-v1.3-4-release.apk') -Raw).Trim() -eq 'old VNC Cast') 'Release publishing changed an existing legacy APK'
    Assert-That (Test-Path -LiteralPath (Join-Path $legacy 'unrelated.apk')) 'Release publishing removed an unrelated legacy APK'
    Assert-That (-not (Test-Path -LiteralPath (Join-Path $legacy 'vnccast-v1.0.3-3-release.apk'))) 'VNC Cast wrapper published a legacy bridge APK'
    Assert-That (@(Get-ChildItem -LiteralPath $legacy -File).Count -eq 2) 'A release wrapper wrote into the legacy update folder'
    $vncScript = $ReleaseScripts | Where-Object { (Split-Path $_ -Leaf) -eq 'deploy-android.ps1' } | Select-Object -First 1
    if ($vncScript) {
        $compatibilityRoot = Join-Path $root 'compatibility'
        & $vncScript -ProjectPath $root -UpdatesDir (Join-Path $compatibilityRoot '.Updates') -DistDirectory (Join-Path $root 'dist') -SkipBuild
        Assert-That (Test-Path -LiteralPath (Join-Path $compatibilityRoot '.appupdates\apps\com.kkc.vnccast\com.kkc.vnccast-v1.0.3-3.apk')) 'VNC Cast UpdatesDir option did not place the canonical feed beside the supplied path'
    }
    $before = (Get-FileHash -LiteralPath $manifestPath).Hash
    Set-Content -LiteralPath (Join-Path $root 'app\build.gradle.kts') -Value 'versionCode = 4; versionName = "1.0.4"'
    foreach ($script in $ReleaseScripts) {
        $failed = $false
        $arguments = @{ProjectPath=$root; FeedRoot=$feed; SkipBuild=$true}
        if ($script -eq $vncScript) { $arguments.DistDirectory = Join-Path $root 'dist' }
        try { & $script @arguments } catch { $failed=$true }
        Assert-That $failed 'Stale built APK was published'
        Assert-That ((Get-FileHash -LiteralPath $manifestPath).Hash -eq $before) 'Metadata failure changed manifest'
    }

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
    Write-Output 'PASS: all release wrappers publish canonical feeds only, preserve the legacy folder, and keep stale build rejection and debug cleanup'
} finally {
    $resolved = [System.IO.Path]::GetFullPath($root)
    if (-not $resolved.StartsWith([System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

