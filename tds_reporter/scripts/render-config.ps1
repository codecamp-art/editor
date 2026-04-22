param(
    [string]$TemplatePath = (Join-Path $PSScriptRoot "..\config\tds_reporter.properties.template"),
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\config\tds_reporter.properties")
)

$resolvedTemplatePath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($TemplatePath)
$resolvedOutputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
$outputDirectory = Split-Path -Parent $resolvedOutputPath

if (-not (Test-Path -LiteralPath $resolvedTemplatePath)) {
    throw "Template file does not exist: $resolvedTemplatePath"
}

if ($outputDirectory) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}

$templateContent = [System.IO.File]::ReadAllText($resolvedTemplatePath)
$renderedContent = [System.Text.RegularExpressions.Regex]::Replace(
    $templateContent,
    '\$\{([^}:]+)(?::([^}]*))?\}',
    {
        param($match)

        $name = $match.Groups[1].Value
        $fallback = if ($match.Groups[2].Success) { $match.Groups[2].Value } else { "" }
        $value = [System.Environment]::GetEnvironmentVariable($name)

        if ([string]::IsNullOrEmpty($value)) {
            return $fallback
        }

        return $value
    })

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($resolvedOutputPath, $renderedContent, $utf8NoBom)

Write-Host "Rendered $resolvedOutputPath from $resolvedTemplatePath"
