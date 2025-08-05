# Simplified PowerShell script to fetch out.dat files and extract FIX messages
# Based on the user's existing PowerShell pattern

param(
    [string]$RemoteComputer = "localhost",
    [string]$RootPath = "C:\fix_logs",
    [string]$LocalPath = ".\downloaded_fix_files",
    [PSCredential]$Credential = $null
)

function Get-RemoteOutDatFiles {
    param(
        [string]$Computer,
        [string]$SearchPath,
        [PSCredential]$Cred
    )
    
    $scriptBlock = {
        param($path)
        Get-ChildItem -Path $path -Recurse -Filter "out.dat" -File | 
        ForEach-Object { $_.FullName }
    }
    
    if ($Cred) {
        return Invoke-Command -ComputerName $Computer -ScriptBlock $scriptBlock -ArgumentList $SearchPath -Credential $Cred
    } else {
        return Invoke-Command -ComputerName $Computer -ScriptBlock $scriptBlock -ArgumentList $SearchPath
    }
}

function Fetch-RemoteFile {
    param(
        [string]$Computer,
        [string]$RemoteFilePath,
        [PSCredential]$Cred
    )
    
    # Similar to your existing script pattern
    $script = @"
        `$file = '$RemoteFilePath'
        if (Test-Path -LiteralPath `$file -PathType Leaf) {
            `$buffer_size = 65536
            `$offset = 0
            `$stream = New-Object -TypeName IO.FileStream(`$file, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
            `$stream.Seek(`$offset, [System.IO.SeekOrigin]::Begin) > `$null
            `$buffer = New-Object -TypeName byte[] `$buffer_size
            
            `$allBytes = @()
            do {
                `$bytes_read = `$stream.Read(`$buffer, 0, `$buffer_size)
                if (`$bytes_read -gt 0) {
                    `$bytes = `$buffer[0..(`$bytes_read - 1)]
                    `$allBytes += `$bytes
                }
            } while (`$bytes_read -gt 0)
            
            `$stream.Close()
            [System.Convert]::ToBase64String(`$allBytes)
        } else {
            Write-Error "File does not exist"
        }
"@

    if ($Cred) {
        $result = Invoke-Command -ComputerName $Computer -ScriptBlock ([scriptblock]::Create($script)) -Credential $Cred
    } else {
        $result = Invoke-Command -ComputerName $Computer -ScriptBlock ([scriptblock]::Create($script))
    }
    
    return $result
}

function Extract-FixFromContent {
    param([byte[]]$Content)
    
    $text = [System.Text.Encoding]::ASCII.GetString($Content)
    $fixMessages = @()
    
    # Split by common FIX message delimiters
    $lines = $text -split "`n|`r`n|`r"
    
    foreach ($line in $lines) {
        if ($line -match "^8=") {  # FIX message starts with BeginString
            $fixMessages += @{
                Raw = $line.Trim()
                Parsed = Parse-FixMessage $line.Trim()
            }
        }
    }
    
    return $fixMessages
}

function Parse-FixMessage {
    param([string]$FixString)
    
    $soh = [char]1
    $fields = @{}
    
    # Replace common delimiters with SOH if needed
    $FixString = $FixString -replace '\|', $soh
    
    $pairs = $FixString -split $soh
    foreach ($pair in $pairs) {
        if ($pair -match "^(\d+)=(.*)$") {
            $fields[$matches[1]] = $matches[2]
        }
    }
    
    return $fields
}

# Main execution
Write-Host "Searching for out.dat files on $RemoteComputer..." -ForegroundColor Yellow

try {
    # Find all out.dat files
    $outDatFiles = Get-RemoteOutDatFiles -Computer $RemoteComputer -SearchPath $RootPath -Cred $Credential
    
    if (-not $outDatFiles) {
        Write-Host "No out.dat files found!" -ForegroundColor Red
        exit
    }
    
    Write-Host "Found $($outDatFiles.Count) out.dat files" -ForegroundColor Green
    
    # Create local directory
    if (-not (Test-Path $LocalPath)) {
        New-Item -ItemType Directory -Path $LocalPath -Force | Out-Null
    }
    
    $allFixMessages = @()
    
    foreach ($file in $outDatFiles) {
        Write-Host "Processing: $file" -ForegroundColor Cyan
        
        try {
            # Fetch file content (base64 encoded)
            $base64Content = Fetch-RemoteFile -Computer $RemoteComputer -RemoteFilePath $file -Cred $Credential
            
            if ($base64Content) {
                # Decode content
                $bytes = [System.Convert]::FromBase64String($base64Content)
                
                # Save locally
                $fileName = Split-Path $file -Leaf
                $localFile = Join-Path $LocalPath $fileName
                [System.IO.File]::WriteAllBytes($localFile, $bytes)
                
                Write-Host "  Saved to: $localFile" -ForegroundColor Green
                
                # Extract FIX messages
                $fixMessages = Extract-FixFromContent -Content $bytes
                if ($fixMessages.Count -gt 0) {
                    Write-Host "  Found $($fixMessages.Count) FIX messages" -ForegroundColor Green
                    $allFixMessages += $fixMessages
                }
            }
        }
        catch {
            Write-Host "  Error processing $file : $_" -ForegroundColor Red
        }
    }
    
    # Save all FIX messages
    if ($allFixMessages.Count -gt 0) {
        $jsonFile = Join-Path $LocalPath "all_fix_messages.json"
        $allFixMessages | ConvertTo-Json -Depth 5 | Out-File $jsonFile
        Write-Host "Saved $($allFixMessages.Count) FIX messages to $jsonFile" -ForegroundColor Green
    }
}
catch {
    Write-Host "Error: $_" -ForegroundColor Red
}