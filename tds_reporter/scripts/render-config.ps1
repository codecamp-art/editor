param(
    [string]$EnvName = "",
    [string]$TemplatePath = "",
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\config\report.properties")
)

if ([string]::IsNullOrWhiteSpace($TemplatePath)) {
    if (-not [string]::IsNullOrWhiteSpace($EnvName) -and
        ($EnvName.Contains('\') -or $EnvName.Contains('/') -or $EnvName.EndsWith('.properties'))) {
        $TemplatePath = $EnvName
        $EnvName = ""
    }
}

if ([string]::IsNullOrWhiteSpace($TemplatePath)) {
    if ([string]::IsNullOrWhiteSpace($EnvName)) {
        $EnvName = [System.Environment]::GetEnvironmentVariable("APP_ENV")
    }

    if ([string]::IsNullOrWhiteSpace($EnvName)) {
        throw "Use -EnvName dev|qa|prod or -TemplatePath path"
    }

    $TemplatePath = (Join-Path $PSScriptRoot "..\config\$EnvName.properties")
}

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
