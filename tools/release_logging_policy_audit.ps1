[CmdletBinding()]
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$sourceRoots = @(
    (Join-Path $Root "app\src\main\java"),
    (Join-Path $Root "updater-agent\src\main\java")
)
$canonicalLoggers = @(
    (Join-Path $Root "app\src\main\java\com\kkc\sheettracker\logging\AppLog.kt"),
    (Join-Path $Root "updater-agent\src\main\java\com\kkc\updateragent\logging\AgentLog.kt")
) | ForEach-Object { [System.IO.Path]::GetFullPath($_) }

$routineAndroidLogPattern = '\b(?:android\.util\.)?Log\.(?:v|d|i|println)\s*\('
# Keep System.err fallback output available for reporting a failure to the Android logger itself.
$bareDebugPrintPattern = '(?<![\w.])println\s*\('
$violations = [System.Collections.Generic.List[string]]::new()

foreach ($sourceRoot in $sourceRoots) {
    if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) {
        throw "Missing production source root: $sourceRoot"
    }

    Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.kt' -File | ForEach-Object {
        $path = [System.IO.Path]::GetFullPath($_.FullName)
        if ($canonicalLoggers -contains $path) {
            return
        }

        $lineNumber = 0
        Get-Content -LiteralPath $_.FullName | ForEach-Object {
            $lineNumber++
            $line = $_
            if ($line -match $routineAndroidLogPattern) {
                $violations.Add("direct routine Android log API: $path`:$lineNumber`: $line")
            }
            if ($line -match $bareDebugPrintPattern) {
                $violations.Add("bare production debug print: $path`:$lineNumber`: $line")
            }
        }
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "Release logging policy audit passed: no routine direct Android logs or bare production debug prints."
