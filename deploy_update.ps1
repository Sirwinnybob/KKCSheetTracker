param(
    [string]$ProjectPath = $PSScriptRoot,
    [string]$AppModule = 'app',
    [string]$PackageName = 'com.kkc.sheettracker',
    [string]$RolloutChannel = 'stable',
    [string]$FeedRoot = 'Y:\Ready Jobs\.appupdates\apps',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
if (-not $SkipBuild) {
    Push-Location $ProjectPath
    try {
        & (Join-Path $ProjectPath 'gradlew.bat') ":$AppModule`:assembleRelease" --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed; published APKs were left intact' }
    } finally { Pop-Location }
}
$moduleRoot = Join-Path $ProjectPath $AppModule
$buildFile = Join-Path $moduleRoot 'build.gradle.kts'
if (-not (Test-Path -LiteralPath $buildFile)) { $buildFile = Join-Path $moduleRoot 'build.gradle' }
$content = Get-Content -LiteralPath $buildFile -Raw
if ($content -notmatch 'versionCode\s*=?\s*(\d+)') { throw 'Cannot parse versionCode' }
$versionCode = [long]$matches[1]
if ($content -notmatch 'versionName\s*=?\s*"([^"]+)"') { throw 'Cannot parse versionName' }
$versionName = $matches[1]
$releaseRoot = Join-Path $moduleRoot 'build\outputs\apk\release'
$source = Join-Path $releaseRoot "$AppModule-release.apk"
$metadata = Get-Content -LiteralPath (Join-Path $releaseRoot 'output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne $PackageName -or $metadata.variantName -ne 'release' -or
        @($metadata.elements).Count -ne 1 -or $metadata.elements[0].versionCode -ne $versionCode -or
        $metadata.elements[0].versionName -ne $versionName -or $metadata.elements[0].outputFile -ne "$AppModule-release.apk") {
    throw 'Release APK metadata does not match the requested package/version'
}
$publish = @{
    SourceApk=$source; FeedRoot=$FeedRoot; PackageName=$PackageName
    VersionCode=$versionCode; VersionName=$versionName; RolloutChannel=$RolloutChannel
}
& (Join-Path $PSScriptRoot 'tools\publish_app_update.ps1') @publish
