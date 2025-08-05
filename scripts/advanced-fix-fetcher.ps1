# Advanced FIX file fetcher with configuration file support
param(
    [string]$ConfigPath = ".\config.json",
    [string]$ServerName = "",
    [string]$OutputPath = "",
    [switch]$AllServers = $false,
    [PSCredential]$Credential = $null
)

# Load configuration
function Load-Config {
    param([string]$Path)
    
    if (-not (Test-Path $Path)) {
        Write-Error "Configuration file not found: $Path"
        return $null
    }
    
    try {
        return Get-Content $Path | ConvertFrom-Json
    }
    catch {
        Write-Error "Failed to parse configuration file: $_"
        return $null
    }
}

# Enhanced FIX message parser using configuration
function Parse-FixMessage {
    param(
        [string]$FixString,
        [hashtable]$FieldDefinitions,
        [hashtable]$MessageTypes
    )
    
    $soh = [char]1
    $fields = @{}
    $parsedFields = @{}
    
    # Handle different delimiters
    $FixString = $FixString -replace '\|', $soh
    $FixString = $FixString -replace '\^A', $soh
    
    $pairs = $FixString -split $soh
    foreach ($pair in $pairs) {
        if ($pair -match "^(\d+)=(.*)$") {
            $tag = $matches[1]
            $value = $matches[2]
            $fields[$tag] = $value
            
            # Add field description if available
            if ($FieldDefinitions.ContainsKey($tag)) {
                $parsedFields[$FieldDefinitions[$tag]] = $value
            }
        }
    }
    
    # Get message type description
    $msgType = $fields["35"]
    $msgTypeDesc = if ($msgType -and $MessageTypes.ContainsKey($msgType)) { $MessageTypes[$msgType] } else { "Unknown" }
    
    return @{
        RawFields = $fields
        ParsedFields = $parsedFields
        MessageType = $msgType
        MessageTypeDescription = $msgTypeDesc
        BeginString = $fields["8"]
        SenderCompID = $fields["49"]
        TargetCompID = $fields["56"]
        SendingTime = $fields["52"]
        Symbol = $fields["55"]
        Side = $fields["54"]
        OrderQty = $fields["38"]
        Price = $fields["44"]
        ClOrdID = $fields["11"]
        ExecID = $fields["17"]
        ExecType = $fields["150"]
        OrdStatus = $fields["39"]
        RawMessage = $FixString
    }
}

# Fetch files from remote server with retry logic
function Fetch-FilesFromServer {
    param(
        [object]$Server,
        [object]$Settings,
        [PSCredential]$Cred
    )
    
    Write-Host "Processing server: $($Server.name) ($($Server.hostname))" -ForegroundColor Green
    
    $maxRetries = $Settings.retryAttempts
    $timeout = $Settings.timeoutSeconds
    
    for ($attempt = 1; $attempt -le $maxRetries; $attempt++) {
        try {
            Write-Host "  Attempt $attempt/$maxRetries" -ForegroundColor Yellow
            
            # Find files
            $scriptBlock = {
                param($path, $patterns)
                $allFiles = @()
                foreach ($pattern in $patterns) {
                    $files = Get-ChildItem -Path $path -Recurse -Filter $pattern -File -ErrorAction SilentlyContinue
                    $allFiles += $files
                }
                return $allFiles | Select-Object FullName, Length, LastWriteTime
            }
            
            $remoteFiles = if ($Cred) {
                Invoke-Command -ComputerName $Server.hostname -ScriptBlock $scriptBlock -ArgumentList $Server.fixLogPath, $Settings.filePatterns -Credential $Cred -TimeoutSec $timeout
            } else {
                Invoke-Command -ComputerName $Server.hostname -ScriptBlock $scriptBlock -ArgumentList $Server.fixLogPath, $Settings.filePatterns -TimeoutSec $timeout
            }
            
            Write-Host "  Found $($remoteFiles.Count) files" -ForegroundColor Cyan
            return $remoteFiles
        }
        catch {
            Write-Host "  Attempt $attempt failed: $_" -ForegroundColor Red
            if ($attempt -eq $maxRetries) {
                throw
            }
            Start-Sleep -Seconds (2 * $attempt)  # Exponential backoff
        }
    }
}

# Main processing function
function Process-Server {
    param(
        [object]$Server,
        [object]$Config,
        [string]$OutputBasePath,
        [PSCredential]$Cred
    )
    
    if (-not $Server.enabled) {
        Write-Host "Skipping disabled server: $($Server.name)" -ForegroundColor Gray
        return
    }
    
    try {
        # Create server-specific output directory
        $serverOutputPath = Join-Path $OutputBasePath $Server.name
        if (-not (Test-Path $serverOutputPath)) {
            New-Item -ItemType Directory -Path $serverOutputPath -Force | Out-Null
        }
        
        # Fetch file list
        $remoteFiles = Fetch-FilesFromServer -Server $Server -Settings $Config.settings -Cred $Cred
        
        if (-not $remoteFiles -or $remoteFiles.Count -eq 0) {
            Write-Host "  No files found on $($Server.name)" -ForegroundColor Yellow
            return
        }
        
        $allFixMessages = @()
        $processedCount = 0
        
        foreach ($file in $remoteFiles) {
            $processedCount++
            Write-Host "  [$processedCount/$($remoteFiles.Count)] $($file.FullName)" -ForegroundColor Cyan
            
            try {
                # Fetch file content
                $base64Content = Invoke-Command -ComputerName $Server.hostname -ScriptBlock {
                    param($filePath)
                    
                    if (Test-Path -LiteralPath $filePath -PathType Leaf) {
                        $bytes = [System.IO.File]::ReadAllBytes($filePath)
                        [System.Convert]::ToBase64String($bytes)
                    } else {
                        throw "File not found: $filePath"
                    }
                } -ArgumentList $file.FullName -Credential $Cred
                
                # Decode and save
                $fileBytes = [System.Convert]::FromBase64String($base64Content)
                $localFileName = "$($Server.name)_$(Split-Path $file.FullName -Leaf)"
                $localFilePath = Join-Path $serverOutputPath $localFileName
                [System.IO.File]::WriteAllBytes($localFilePath, $fileBytes)
                
                # Extract FIX messages
                if ($Config.settings.extractFixMessages) {
                    $content = [System.Text.Encoding]::ASCII.GetString($fileBytes)
                    $lines = $content -split "`n|`r`n|`r"
                    
                    foreach ($line in $lines) {
                        if ($line -match "^8=") {
                            $fixMessage = Parse-FixMessage -FixString $line.Trim() -FieldDefinitions $Config.fixMessageFields -MessageTypes $Config.messageTypes
                            $fixMessage.SourceFile = $file.FullName
                            $fixMessage.SourceServer = $Server.name
                            $allFixMessages += $fixMessage
                        }
                    }
                }
                
                Write-Host "    Saved: $localFilePath" -ForegroundColor Green
            }
            catch {
                Write-Host "    Error: $_" -ForegroundColor Red
            }
        }
        
        # Save FIX messages for this server
        if ($allFixMessages.Count -gt 0) {
            $jsonPath = Join-Path $serverOutputPath "fix_messages.json"
            $allFixMessages | ConvertTo-Json -Depth 10 | Out-File $jsonPath -Encoding UTF8
            
            $csvPath = Join-Path $serverOutputPath "fix_messages.csv"
            $allFixMessages | Select-Object SourceServer, SourceFile, MessageType, MessageTypeDescription, Symbol, Side, OrderQty, Price, ClOrdID, ExecID, SendingTime |
            Export-Csv $csvPath -NoTypeInformation -Encoding UTF8
            
            Write-Host "  Extracted $($allFixMessages.Count) FIX messages" -ForegroundColor Green
        }
        
    }
    catch {
        Write-Host "Error processing server $($Server.name): $_" -ForegroundColor Red
    }
}

# Main execution
try {
    Write-Host "=== Advanced FIX File Fetcher ===" -ForegroundColor Green
    
    # Load configuration
    $config = Load-Config -Path $ConfigPath
    if (-not $config) {
        exit 1
    }
    
    # Determine output path
    $outputPath = if ($OutputPath) { $OutputPath } else { $config.settings.localOutputPath }
    if (-not (Test-Path $outputPath)) {
        New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
    }
    
    Write-Host "Output path: $outputPath" -ForegroundColor Cyan
    
    # Get credentials if needed
    if (-not $Credential) {
        $Credential = Get-Credential -Message "Enter credentials for remote servers"
    }
    
    # Process servers
    if ($AllServers) {
        foreach ($server in $config.servers) {
            Process-Server -Server $server -Config $config -OutputBasePath $outputPath -Cred $Credential
        }
    }
    elseif ($ServerName) {
        $server = $config.servers | Where-Object { $_.name -eq $ServerName }
        if ($server) {
            Process-Server -Server $server -Config $config -OutputBasePath $outputPath -Cred $Credential
        } else {
            Write-Error "Server '$ServerName' not found in configuration"
        }
    }
    else {
        Write-Host "Available servers:" -ForegroundColor Yellow
        foreach ($server in $config.servers) {
            $status = if ($server.enabled) { "Enabled" } else { "Disabled" }
            Write-Host "  $($server.name) - $($server.description) [$status]" -ForegroundColor White
        }
        Write-Host "`nUse -ServerName <name> or -AllServers to process" -ForegroundColor Yellow
    }
    
    Write-Host "Processing completed!" -ForegroundColor Green
}
catch {
    Write-Host "Fatal error: $_" -ForegroundColor Red
    exit 1
}