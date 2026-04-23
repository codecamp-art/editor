param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("dev", "qa", "prod")]
    [string]$EnvName,

    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$ReportArgs
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ((Test-Path -LiteralPath (Join-Path $scriptDir "bin")) -and
    (Test-Path -LiteralPath (Join-Path $scriptDir "config"))) {
    $packageRoot = $scriptDir
} else {
    $packageRoot = Split-Path -Parent $scriptDir
}

$renderScript = Join-Path $packageRoot "scripts\\render-config.ps1"
$outputPath = Join-Path $packageRoot "config\\report.properties"
$reportExe = Join-Path $packageRoot "bin\\report.exe"

& $renderScript -EnvName $EnvName -OutputPath $outputPath
& $reportExe @ReportArgs
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
