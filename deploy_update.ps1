param(
    [string]$ProjectPath = "C:\Scripts\KKCSheetTracker",
    [string]$AppModule = "app",
    [string]$PackageName = "com.kkc.sheettracker",
    [string]$RolloutChannel = "stable",
    [string]$FeedRoot = "Y:\Ready Jobs\.appupdates\apps",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

function Parse-BuildValue {
    param(
        [string]$Content,
        [string]$Pattern,
        [string]$Label
    )
    if ($Content -match $Pattern) {
        return $matches[1]
    }
    throw "Could not parse $Label from build.gradle.kts"
}

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

Set-Location $ProjectPath
$gradlew = Join-Path $ProjectPath "gradlew.bat"
$moduleBuildFile = Join-Path $ProjectPath "$AppModule\build.gradle.kts"
$releaseApk = Join-Path $ProjectPath "$AppModule\build\outputs\apk\release\$AppModule-release.apk"
$unsignedApk = Join-Path $ProjectPath "$AppModule\build\outputs\apk\release\$AppModule-release-unsigned.apk"

if (-not $SkipBuild) {
    Write-Host "Building $AppModule release APK..." -ForegroundColor Cyan
    & $gradlew ":$AppModule`:assembleRelease" --rerun-tasks --no-build-cache
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed"
    }
}

$buildContent = Get-Content -Path $moduleBuildFile -Raw
$versionCode = [int64](Parse-BuildValue -Content $buildContent -Pattern 'versionCode\s*=?\s*(\d+)' -Label "versionCode")
$versionName = Parse-BuildValue -Content $buildContent -Pattern 'versionName\s*=?\s*"([^"]+)"' -Label "versionName"

$sourceApk = if (Test-Path -LiteralPath $releaseApk) { $releaseApk } elseif (Test-Path -LiteralPath $unsignedApk) { $unsignedApk } else { $null }
if (-not $sourceApk) {
    throw "Could not find release APK at $releaseApk or $unsignedApk"
}

Ensure-Directory -Path $FeedRoot
$appFeedDir = Join-Path $FeedRoot $PackageName
Ensure-Directory -Path $appFeedDir

$safeVersionName = ($versionName -replace '[^0-9A-Za-z\.\-_]', '_')
$apkFileName = "$PackageName-v$safeVersionName-$versionCode.apk"
$targetApk = Join-Path $appFeedDir $apkFileName
$tempApk = "$targetApk.tmp"

Copy-Item -LiteralPath $sourceApk -Destination $tempApk -Force
Move-Item -LiteralPath $tempApk -Destination $targetApk -Force

$sha256 = (Get-FileHash -LiteralPath $targetApk -Algorithm SHA256).Hash.ToLowerInvariant()

$manifestPath = Join-Path $FeedRoot "manifest.json"
$manifestTemp = "$manifestPath.tmp"
$nowIso = (Get-Date).ToUniversalTime().ToString("o")

if (Test-Path -LiteralPath $manifestPath) {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -Depth 100
} else {
    $manifest = [pscustomobject]@{
        schemaVersion = "v1"
        generatedAt = $nowIso
        apps = @()
        history = @()
    }
}

if (-not $manifest.apps) { $manifest | Add-Member -NotePropertyName apps -NotePropertyValue @() -Force }
if (-not $manifest.history) { $manifest | Add-Member -NotePropertyName history -NotePropertyValue @() -Force }
if (-not $manifest.schemaVersion) { $manifest | Add-Member -NotePropertyName schemaVersion -NotePropertyValue "v1" -Force }

$newEntry = [pscustomobject]@{
    packageName = $PackageName
    versionCode = $versionCode
    versionName = $versionName
    apkFile = $apkFileName
    sha256 = $sha256
    minRequiredVersionCode = $versionCode
    rolloutChannel = $RolloutChannel
    publishedAt = $nowIso
    allowDowngrade = $false
}

$existingApps = @($manifest.apps)
$retainedApps = @()
foreach ($item in $existingApps) {
    if ($item.packageName -eq $PackageName -and $item.rolloutChannel -eq $RolloutChannel) {
        $manifest.history += $item
    } else {
        $retainedApps += $item
    }
}
$retainedApps += $newEntry
$manifest.apps = $retainedApps
$manifest.generatedAt = $nowIso

if ($manifest.history.Count -gt 200) {
    $manifest.history = @($manifest.history | Select-Object -Last 200)
}

$manifestJson = $manifest | ConvertTo-Json -Depth 100
Set-Content -LiteralPath $manifestTemp -Value $manifestJson -Encoding UTF8
Move-Item -LiteralPath $manifestTemp -Destination $manifestPath -Force

Write-Host "Published update:" -ForegroundColor Green
Write-Host "  Package: $PackageName"
Write-Host "  Version: $versionName ($versionCode)"
Write-Host "  Channel: $RolloutChannel"
Write-Host "  APK: $targetApk"
Write-Host "  SHA256: $sha256"
Write-Host "  Manifest: $manifestPath"
