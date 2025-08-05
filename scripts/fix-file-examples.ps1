# Examples and usage patterns for fetching remote FIX files

# Example 1: Basic usage - local machine
Write-Host "=== Example 1: Local Machine ===" -ForegroundColor Green
.\simple-fix-file-fetcher.ps1 -RootPath "C:\fix_logs" -LocalPath ".\local_fix_files"

# Example 2: Remote machine with credentials
Write-Host "`n=== Example 2: Remote Machine with Credentials ===" -ForegroundColor Green
$cred = Get-Credential -Message "Enter credentials for remote server"
.\fetch-remote-fix-files.ps1 -RemoteComputer "PROD-FIX-01" -RootPath "D:\Trading\FIX_Logs" -LocalOutputPath "C:\FIX_Analysis" -Credential $cred

# Example 3: Multiple remote servers
Write-Host "`n=== Example 3: Multiple Servers ===" -ForegroundColor Green
$servers = @("PROD-FIX-01", "PROD-FIX-02", "UAT-FIX-01")
$credentials = Get-Credential

foreach ($server in $servers) {
    Write-Host "Processing server: $server" -ForegroundColor Yellow
    $outputPath = "C:\FIX_Analysis\$server"
    .\fetch-remote-fix-files.ps1 -RemoteComputer $server -RootPath "D:\fix_logs" -LocalOutputPath $outputPath -Credential $credentials
}

# Example 4: Date-specific folders
Write-Host "`n=== Example 4: Date-specific Processing ===" -ForegroundColor Green
$today = Get-Date -Format "yyyyMMdd"
$yesterday = (Get-Date).AddDays(-1).ToString("yyyyMMdd")

foreach ($date in @($today, $yesterday)) {
    $remotePath = "D:\fix_logs\$date"
    $localPath = "C:\FIX_Analysis\$date"
    
    Write-Host "Processing date: $date" -ForegroundColor Yellow
    .\fetch-remote-fix-files.ps1 -RemoteComputer "PROD-FIX-01" -RootPath $remotePath -LocalOutputPath $localPath -Credential $credentials
}

# Example 5: Custom file patterns
Write-Host "`n=== Example 5: Custom File Patterns ===" -ForegroundColor Green
# Look for different file types
$patterns = @("out.dat", "*.fix", "*.log")
foreach ($pattern in $patterns) {
    Write-Host "Processing pattern: $pattern" -ForegroundColor Yellow
    .\fetch-remote-fix-files.ps1 -RemoteComputer "PROD-FIX-01" -RootPath "D:\fix_logs" -LocalOutputPath "C:\FIX_Analysis\$pattern" -FilePattern $pattern -Credential $credentials
}

# Example 6: Process and analyze FIX messages
Write-Host "`n=== Example 6: FIX Message Analysis ===" -ForegroundColor Green

function Analyze-FixMessages {
    param([string]$JsonPath)
    
    if (Test-Path $JsonPath) {
        $messages = Get-Content $JsonPath | ConvertFrom-Json
        
        Write-Host "Total messages: $($messages.Count)" -ForegroundColor Cyan
        
        # Group by message type
        $messageTypes = $messages | Group-Object MessageType | Sort-Object Count -Descending
        Write-Host "Message types:" -ForegroundColor Cyan
        foreach ($type in $messageTypes) {
            Write-Host "  $($type.Name): $($type.Count)" -ForegroundColor White
        }
        
        # Group by symbol
        $symbols = $messages | Where-Object {$_.Symbol} | Group-Object Symbol | Sort-Object Count -Descending | Select-Object -First 10
        Write-Host "Top symbols:" -ForegroundColor Cyan
        foreach ($symbol in $symbols) {
            Write-Host "  $($symbol.Name): $($symbol.Count)" -ForegroundColor White
        }
    }
}

# Analyze the downloaded messages
Analyze-FixMessages -JsonPath "C:\FIX_Analysis\all_fix_messages.json"

# Example 7: Scheduled processing
Write-Host "`n=== Example 7: Scheduled Task Setup ===" -ForegroundColor Green
@"
# Create a scheduled task to run daily at 2 AM
`$action = New-ScheduledTaskAction -Execute 'PowerShell.exe' -Argument '-File "C:\Scripts\fetch-remote-fix-files.ps1" -RemoteComputer "PROD-FIX-01" -RootPath "D:\fix_logs" -LocalOutputPath "C:\FIX_Analysis\Daily"'
`$trigger = New-ScheduledTaskTrigger -Daily -At 2AM
`$principal = New-ScheduledTaskPrincipal -UserId "DOMAIN\ServiceAccount" -LogonType ServiceAccount
`$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Hours 2)

Register-ScheduledTask -Action `$action -Trigger `$trigger -Principal `$principal -Settings `$settings -TaskName "Daily FIX File Fetch" -Description "Fetch FIX files from production servers daily"
"@ | Out-File "setup-scheduled-task.ps1"

Write-Host "Scheduled task setup script created: setup-scheduled-task.ps1" -ForegroundColor Green