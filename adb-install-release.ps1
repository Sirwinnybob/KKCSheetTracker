# Build release APK and deploy to connected devices
$ErrorActionPreference = "Stop"

$projectDir = $PSScriptRoot
$apkPath = "$projectDir\app\build\outputs\apk\release\app-release.apk"

Write-Host "Building release APK..." -ForegroundColor Green
& "$projectDir\gradlew.bat" assembleRelease

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $apkPath)) {
    Write-Host "APK not found at $apkPath" -ForegroundColor Red
    exit 1
}

Write-Host "Getting connected devices..." -ForegroundColor Green
$devices = & adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\bdevice$' } | ForEach-Object { ($_ -split '\s+')[0] }

if ($devices.Count -eq 0) {
    Write-Host "No devices connected" -ForegroundColor Red
    exit 1
}

Write-Host "Found $($devices.Count) device(s)" -ForegroundColor Green

foreach ($device in $devices) {
    Write-Host "Installing to $device..." -ForegroundColor Green
    & adb -s $device install -r $apkPath

    if ($LASTEXITCODE -eq 0) {
        Write-Host "✓ Installed to $device" -ForegroundColor Green
    } else {
        Write-Host "✗ Failed to install to $device" -ForegroundColor Red
    }
}

Write-Host "Done" -ForegroundColor Green
