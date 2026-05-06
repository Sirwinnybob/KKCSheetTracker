$ErrorActionPreference = "Stop"

# Paths
$projectPath = "C:\Scripts\KKCSheetTracker"
$updateDir = "Y:\Ready Jobs\.Updates"
$gradlew = "$projectPath\gradlew.bat"

# 1. Build Release APK
Write-Host "Building Release APK..." -ForegroundColor Cyan
Set-Location $projectPath
& $gradlew assembleRelease --rerun-tasks --no-build-cache

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

# 2. Get Version Info
$buildFile = "$projectPath\app\build.gradle.kts"
$content = Get-Content $buildFile -Raw
if ($content -match 'versionCode\s*=?\s*(\d+)') {
    $versionCode = $matches[1]
}
else {
    Write-Host "Could not parse versionCode!" -ForegroundColor Red
    exit 1
}
if ($content -match 'versionName\s*=?\s*"([^"]+)"') {
    $versionName = $matches[1]
}
else {
    Write-Host "Could not parse versionName!" -ForegroundColor Red
    exit 1
}

Write-Host "Detected Version: $versionName ($versionCode) [RELEASE]" -ForegroundColor Green

# 3. Create Hidden Update Directory
if (-not (Test-Path $updateDir)) {
    Write-Host "Creating update directory: $updateDir" -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $updateDir | Out-Null
}

if (Test-Path $updateDir) {
    try {
        $item = Get-Item $updateDir
        if (($item.Attributes -band [System.IO.FileAttributes]::Hidden) -ne [System.IO.FileAttributes]::Hidden) {
            $item.Attributes = $item.Attributes -bor [System.IO.FileAttributes]::Hidden
        }
    }
    catch {
        Write-Host "Warning: Could not set Hidden attribute on $updateDir. Proceeding anyway." -ForegroundColor Yellow
    }
}
else {
    Write-Host "Error: Failed to create $updateDir" -ForegroundColor Red
    exit 1
}

# 4. Clean old KKC APKs only
Write-Host "Cleaning up old KKC APKs in $updateDir..." -ForegroundColor Cyan
Get-ChildItem -Path $updateDir -Filter "*.apk" |
    Where-Object { $_.Name -match "kkc|sheet.?tracker|kkc-sheettracker" } |
    Remove-Item -Force

# 5. Copy APK (signed preferred)
$releaseApk = "$projectPath\app\build\outputs\apk\release\app-release.apk"
$unsignedApk = "$projectPath\app\build\outputs\apk\release\app-release-unsigned.apk"
$safeVersionName = ($versionName -replace '[^0-9A-Za-z\.\-_]', '_')
$targetApk = "$updateDir\kkc-sheettracker-v$safeVersionName-release.apk"

if (Test-Path $releaseApk) {
    Write-Host "Copying signed APK to $targetApk" -ForegroundColor Cyan
    Copy-Item $releaseApk $targetApk -Force
    Write-Host "Success! KKC release update deployed." -ForegroundColor Green
}
elseif (Test-Path $unsignedApk) {
    Write-Host "Copying unsigned APK to $targetApk" -ForegroundColor Yellow
    Copy-Item $unsignedApk $targetApk -Force
    Write-Host "Success! KKC release update deployed (unsigned fallback)." -ForegroundColor Green
}
else {
    Write-Host "Release APK not found at $releaseApk or $unsignedApk" -ForegroundColor Red
    exit 1
}
