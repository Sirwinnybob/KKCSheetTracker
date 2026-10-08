param(
    [Parameter(Mandatory)][string]$SourceApk,
    [Parameter(Mandatory)][string]$FeedRoot,
    [Parameter(Mandatory)][string]$PackageName,
    [Parameter(Mandatory)][long]$VersionCode,
    [Parameter(Mandatory)][string]$VersionName,
    [string]$RolloutChannel = 'stable',
    [string]$LegacyDirectory,
    [string]$LegacyFileName,
    [string]$LegacyPattern
)
# Canonical publisher; local copies in the three repositories avoid a cross-repo runtime dependency.
$ErrorActionPreference = 'Stop'
if ($PackageName -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') { throw 'Invalid package name' }
if (-not (Test-Path -LiteralPath $SourceApk -PathType Leaf) -or (Get-Item -LiteralPath $SourceApk).Length -eq 0) {
    throw "Missing or empty APK: $SourceApk"
}
$feedPath = [System.IO.Path]::GetFullPath($FeedRoot)
$appPath = Join-Path $feedPath $PackageName
New-Item -ItemType Directory -Path $appPath -Force | Out-Null
if ($LegacyDirectory) {
    if (-not $LegacyFileName -or [System.IO.Path]::GetFileName($LegacyFileName) -ne $LegacyFileName -or
            $LegacyFileName -notlike '*.apk' -or -not $LegacyPattern -or
            $LegacyPattern.Contains('/') -or $LegacyPattern.Contains('\')) { throw 'Invalid legacy artifact names' }
    New-Item -ItemType Directory -Path $LegacyDirectory -Force | Out-Null
}
$lockPath = Join-Path $feedPath '.publish.lock'
$publishLock = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::OpenOrCreate,
    [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
try {
    $manifestPath = Join-Path $feedPath 'manifest.json'
    $manifest = if (Test-Path -LiteralPath $manifestPath) {
        Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    } else { [pscustomobject]@{schemaVersion='v1'; generatedAt=''; apps=@(); history=@()} }
    $active = @($manifest.apps | Where-Object { $_.packageName -eq $PackageName -and $_.rolloutChannel -eq $RolloutChannel })
    if (@($active | Where-Object { $_.versionCode -gt $VersionCode }).Count) { throw 'Refusing to publish an older version' }
    $safeVersion = $VersionName -replace '[^0-9A-Za-z.\-_]', '_'
    $fileName = "$PackageName-v$safeVersion-$VersionCode.apk"
    $target = Join-Path $appPath $fileName
    $sourceHash = (Get-FileHash -LiteralPath $SourceApk -Algorithm SHA256).Hash.ToLowerInvariant()
    function Copy-Verified([string]$Destination) {
        $temporary = "$Destination.$([guid]::NewGuid()).tmp"
        try {
            Copy-Item -LiteralPath $SourceApk -Destination $temporary -Force
            if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sourceHash) {
                throw "APK copy verification failed: $Destination"
            }
            Move-Item -LiteralPath $temporary -Destination $Destination -Force
        } finally {
            if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
        }
    }
    Copy-Verified $target
    if ($LegacyDirectory) { Copy-Verified (Join-Path $LegacyDirectory $LegacyFileName) }
    $now = [DateTime]::UtcNow.ToString('o')
    $entry = [pscustomobject]@{
        packageName=$PackageName; versionCode=$VersionCode; versionName=$VersionName
        apkFile=$fileName; sha256=$sourceHash; minRequiredVersionCode=$VersionCode
        rolloutChannel=$RolloutChannel; publishedAt=$now; allowDowngrade=$false
    }
    $manifest.apps = @($manifest.apps | Where-Object {
        $_.packageName -ne $PackageName -or $_.rolloutChannel -ne $RolloutChannel
    }) + @($entry)
    # Deleted APKs must not remain advertised in rollback history.
    $manifest.history = @($manifest.history | Where-Object { $_.packageName -ne $PackageName })
    $manifest.generatedAt = $now
    $temporaryManifest = "$manifestPath.$([guid]::NewGuid()).tmp"
    try {
        $manifest | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $temporaryManifest -Encoding UTF8
        Move-Item -LiteralPath $temporaryManifest -Destination $manifestPath -Force
    } finally {
        if (Test-Path -LiteralPath $temporaryManifest) { Remove-Item -LiteralPath $temporaryManifest -Force }
    }
    # Cleanup happens only after both verified copies and the manifest have been published.
    $keepNames = @($manifest.apps | Where-Object packageName -eq $PackageName | ForEach-Object apkFile)
    Get-ChildItem -LiteralPath $appPath -Filter '*.apk' -File | Where-Object {
        $_.Name -notin $keepNames
    } | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
    if ($LegacyDirectory) {
        Get-ChildItem -LiteralPath $LegacyDirectory -File | Where-Object {
            $_.Name -like $LegacyPattern -and $_.Name -ne $LegacyFileName
        } | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
    }
    Write-Output "Published $PackageName $VersionName ($VersionCode): $target"
} finally { $publishLock.Dispose() }

