# PowerShell script to fetch all out.dat files from remote systems and extract FIX messages
# Usage: .\fetch-remote-fix-files.ps1 -RemoteComputer "server01" -RootPath "C:\fix_logs" -LocalOutputPath "C:\local\fix_data"

param(
    [Parameter(Mandatory=$true)]
    [string]$RemoteComputer,
    
    [Parameter(Mandatory=$true)]
    [string]$RootPath,
    
    [Parameter(Mandatory=$true)]
    [string]$LocalOutputPath,
    
    [Parameter(Mandatory=$false)]
    [System.Management.Automation.PSCredential]$Credential,
    
    [Parameter(Mandatory=$false)]
    [string]$FilePattern = "out.dat",
    
    [Parameter(Mandatory=$false)]
    [int]$BufferSize = 64KB,
    
    [Parameter(Mandatory=$false)]
    [switch]$ExtractFixMessages = $true
)

# Function to write colored output
function Write-ColorOutput {
    param(
        [string]$Message,
        [string]$Color = "White"
    )
    Write-Host $Message -ForegroundColor $Color
}

# Function to find all files recursively on remote system
function Find-RemoteFiles {
    param(
        [string]$Computer,
        [string]$SearchPath,
        [string]$Pattern,
        [System.Management.Automation.PSCredential]$Cred
    )
    
    Write-ColorOutput "Searching for '$Pattern' files on $Computer under $SearchPath..." "Yellow"
    
    $scriptBlock = {
        param($path, $pattern)
        Get-ChildItem -Path $path -Recurse -Filter $pattern -File -ErrorAction SilentlyContinue | 
        Select-Object FullName, Length, LastWriteTime, Directory
    }
    
    try {
        if ($Cred) {
            $files = Invoke-Command -ComputerName $Computer -ScriptBlock $scriptBlock -ArgumentList $SearchPath, $Pattern -Credential $Cred
        } else {
            $files = Invoke-Command -ComputerName $Computer -ScriptBlock $scriptBlock -ArgumentList $SearchPath, $Pattern
        }
        
        return $files
    }
    catch {
        Write-ColorOutput "Error searching files on $Computer : $_" "Red"
        return @()
    }
}

# Function to fetch file content from remote system
function Get-RemoteFileContent {
    param(
        [string]$Computer,
        [string]$RemoteFilePath,
        [System.Management.Automation.PSCredential]$Cred
    )
    
    $scriptBlock = {
        param($filePath, $bufferSize)
        
        try {
            if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
                return @{ Success = $false; Error = "File does not exist"; Data = $null }
            }
            
            $buffer_size = $bufferSize
            $offset = 0
            $stream = New-Object -TypeName IO.FileStream($filePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
            $stream.Seek($offset, [System.IO.SeekOrigin]::Begin) > $null
            $buffer = New-Object -TypeName byte[] $buffer_size
            
            $allBytes = @()
            do {
                $bytes_read = $stream.Read($buffer, 0, $buffer_size)
                if ($bytes_read -gt 0) {
                    $bytes = $buffer[0..($bytes_read - 1)]
                    $allBytes += $bytes
                }
            } while ($bytes_read -gt 0)
            
            $stream.Close()
            
            $base64Data = [System.Convert]::ToBase64String($allBytes)
            return @{ Success = $true; Error = $null; Data = $base64Data }
        }
        catch {
            if ($stream) { $stream.Close() }
            return @{ Success = $false; Error = $_.Exception.Message; Data = $null }
        }
    }
    
    try {
        if ($Cred) {
            $result = Invoke-Command -ComputerName $Computer -ScriptBlock $scriptBlock -ArgumentList $RemoteFilePath, $BufferSize -Credential $Cred
        } else {
            $result = Invoke-Command -ComputerName $Computer -ScriptBlock $scriptBlock -ArgumentList $RemoteFilePath, $BufferSize
        }
        
        return $result
    }
    catch {
        return @{ Success = $false; Error = $_.Exception.Message; Data = $null }
    }
}

# Function to extract FIX messages from file content
function Extract-FixMessages {
    param(
        [byte[]]$FileBytes,
        [string]$SourceFile
    )
    
    $fixMessages = @()
    $content = [System.Text.Encoding]::ASCII.GetString($FileBytes)
    
    # FIX messages typically start with "8=" (BeginString) and end with a checksum field
    # SOH (Start of Header) character is ASCII 1 (\x01)
    $soh = [char]1
    
    # Split content by potential message boundaries
    $lines = $content -split "`n|`r`n|`r"
    
    foreach ($line in $lines) {
        $line = $line.Trim()
        if ($line -match "^8=") {
            # This looks like a FIX message
            $fields = $line -split $soh
            
            $fixMessage = @{
                SourceFile = $SourceFile
                RawMessage = $line
                Fields = @{}
                MessageType = ""
                Symbol = ""
                Side = ""
                Quantity = ""
                Price = ""
                OrderID = ""
                ExecID = ""
                Timestamp = ""
            }
            
            # Parse FIX fields
            foreach ($field in $fields) {
                if ($field -match "^(\d+)=(.*)$") {
                    $tag = $matches[1]
                    $value = $matches[2]
                    $fixMessage.Fields[$tag] = $value
                    
                    # Extract common fields
                    switch ($tag) {
                        "35" { $fixMessage.MessageType = $value }  # MsgType
                        "55" { $fixMessage.Symbol = $value }       # Symbol
                        "54" { $fixMessage.Side = $value }         # Side
                        "38" { $fixMessage.Quantity = $value }     # OrderQty
                        "44" { $fixMessage.Price = $value }        # Price
                        "11" { $fixMessage.OrderID = $value }      # ClOrdID
                        "17" { $fixMessage.ExecID = $value }       # ExecID
                        "52" { $fixMessage.Timestamp = $value }    # SendingTime
                    }
                }
            }
            
            $fixMessages += $fixMessage
        }
    }
    
    return $fixMessages
}

# Function to save file locally
function Save-LocalFile {
    param(
        [byte[]]$Content,
        [string]$LocalPath
    )
    
    try {
        $directory = Split-Path $LocalPath -Parent
        if (-not (Test-Path $directory)) {
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
        }
        
        [System.IO.File]::WriteAllBytes($LocalPath, $Content)
        return $true
    }
    catch {
        Write-ColorOutput "Error saving file $LocalPath : $_" "Red"
        return $false
    }
}

# Main execution
try {
    Write-ColorOutput "=== Remote FIX File Fetcher ===" "Green"
    Write-ColorOutput "Remote Computer: $RemoteComputer" "Cyan"
    Write-ColorOutput "Root Path: $RootPath" "Cyan"
    Write-ColorOutput "Local Output: $LocalOutputPath" "Cyan"
    Write-ColorOutput "File Pattern: $FilePattern" "Cyan"
    Write-ColorOutput "" "White"
    
    # Create local output directory
    if (-not (Test-Path $LocalOutputPath)) {
        New-Item -ItemType Directory -Path $LocalOutputPath -Force | Out-Null
        Write-ColorOutput "Created local output directory: $LocalOutputPath" "Green"
    }
    
    # Find all matching files on remote system
    $remoteFiles = Find-RemoteFiles -Computer $RemoteComputer -SearchPath $RootPath -Pattern $FilePattern -Cred $Credential
    
    if ($remoteFiles.Count -eq 0) {
        Write-ColorOutput "No '$FilePattern' files found on $RemoteComputer under $RootPath" "Yellow"
        exit 0
    }
    
    Write-ColorOutput "Found $($remoteFiles.Count) '$FilePattern' files" "Green"
    Write-ColorOutput "" "White"
    
    $allFixMessages = @()
    $processedFiles = 0
    $errorCount = 0
    
    foreach ($file in $remoteFiles) {
        $processedFiles++
        Write-ColorOutput "[$processedFiles/$($remoteFiles.Count)] Processing: $($file.FullName)" "Yellow"
        Write-ColorOutput "  Size: $([math]::Round($file.Length / 1KB, 2)) KB, Modified: $($file.LastWriteTime)" "Gray"
        
        # Fetch file content
        $result = Get-RemoteFileContent -Computer $RemoteComputer -RemoteFilePath $file.FullName -Cred $Credential
        
        if (-not $result.Success) {
            Write-ColorOutput "  ERROR: $($result.Error)" "Red"
            $errorCount++
            continue
        }
        
        # Decode base64 content
        try {
            $fileBytes = [System.Convert]::FromBase64String($result.Data)
            Write-ColorOutput "  Downloaded: $($fileBytes.Length) bytes" "Green"
        }
        catch {
            Write-ColorOutput "  ERROR: Failed to decode file content: $_" "Red"
            $errorCount++
            continue
        }
        
        # Create local file path maintaining directory structure
        $relativePath = $file.FullName.Replace($RootPath, "").TrimStart('\', '/')
        $localFilePath = Join-Path $LocalOutputPath $relativePath
        
        # Save file locally
        if (Save-LocalFile -Content $fileBytes -LocalPath $localFilePath) {
            Write-ColorOutput "  Saved to: $localFilePath" "Green"
        } else {
            $errorCount++
            continue
        }
        
        # Extract FIX messages if requested
        if ($ExtractFixMessages) {
            $fixMessages = Extract-FixMessages -FileBytes $fileBytes -SourceFile $file.FullName
            if ($fixMessages.Count -gt 0) {
                Write-ColorOutput "  Extracted $($fixMessages.Count) FIX messages" "Green"
                $allFixMessages += $fixMessages
                
                # Save FIX messages as JSON
                $jsonPath = $localFilePath -replace "\.dat$", "_fix_messages.json"
                $fixMessages | ConvertTo-Json -Depth 10 | Out-File -FilePath $jsonPath -Encoding UTF8
                Write-ColorOutput "  FIX messages saved to: $jsonPath" "Green"
            } else {
                Write-ColorOutput "  No FIX messages found in file" "Yellow"
            }
        }
        
        Write-ColorOutput "" "White"
    }
    
    # Summary
    Write-ColorOutput "=== SUMMARY ===" "Green"
    Write-ColorOutput "Files processed: $processedFiles" "Cyan"
    Write-ColorOutput "Errors: $errorCount" "Cyan"
    Write-ColorOutput "Success rate: $([math]::Round((($processedFiles - $errorCount) / $processedFiles) * 100, 2))%" "Cyan"
    
    if ($ExtractFixMessages -and $allFixMessages.Count -gt 0) {
        Write-ColorOutput "Total FIX messages extracted: $($allFixMessages.Count)" "Cyan"
        
        # Save consolidated FIX messages
        $consolidatedPath = Join-Path $LocalOutputPath "all_fix_messages.json"
        $allFixMessages | ConvertTo-Json -Depth 10 | Out-File -FilePath $consolidatedPath -Encoding UTF8
        Write-ColorOutput "All FIX messages saved to: $consolidatedPath" "Green"
        
        # Create CSV summary
        $csvPath = Join-Path $LocalOutputPath "fix_messages_summary.csv"
        $allFixMessages | Select-Object SourceFile, MessageType, Symbol, Side, Quantity, Price, OrderID, ExecID, Timestamp | 
        Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8
        Write-ColorOutput "FIX messages summary saved to: $csvPath" "Green"
    }
    
}
catch {
    Write-ColorOutput "FATAL ERROR: $_" "Red"
    exit 1
}