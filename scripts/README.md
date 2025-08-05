# FIX File Fetcher Scripts

This collection of PowerShell scripts helps you recursively find and fetch `out.dat` files (or other FIX message files) from remote servers using WinRM, and extract FIX messages from them.

## Scripts Overview

### 1. `simple-fix-file-fetcher.ps1`
Basic script that mirrors your existing PowerShell pattern for fetching remote files.

**Usage:**
```powershell
.\simple-fix-file-fetcher.ps1 -RemoteComputer "SERVER01" -RootPath "C:\fix_logs" -LocalPath ".\downloads"
```

### 2. `fetch-remote-fix-files.ps1`
Full-featured script with comprehensive error handling, logging, and FIX message extraction.

**Usage:**
```powershell
.\fetch-remote-fix-files.ps1 -RemoteComputer "PROD-FIX-01" -RootPath "D:\fix_logs" -LocalOutputPath "C:\Analysis" -Credential $cred
```

### 3. `advanced-fix-fetcher.ps1`
Configuration-driven script that supports multiple servers and advanced FIX parsing.

**Usage:**
```powershell
# Process all servers
.\advanced-fix-fetcher.ps1 -AllServers -Credential $cred

# Process specific server
.\advanced-fix-fetcher.ps1 -ServerName "PROD-FIX-01" -Credential $cred
```

### 4. `fix-file-examples.ps1`
Collection of usage examples and patterns.

### 5. `fetch-fix-files.bat`
Windows batch wrapper for easy command-line usage.

**Usage:**
```cmd
fetch-fix-files.bat SERVER01 "C:\fix_logs" ".\downloads"
```

## Configuration File (`config.json`)

The advanced script uses a JSON configuration file to define servers and settings:

```json
{
  "servers": [
    {
      "name": "PROD-FIX-01",
      "hostname": "prod-fix-server-01.company.com",
      "fixLogPath": "D:\\Trading\\FIX_Logs",
      "enabled": true,
      "description": "Primary production FIX server"
    }
  ],
  "settings": {
    "localOutputPath": "C:\\FIX_Analysis",
    "filePatterns": ["out.dat", "*.fix"],
    "extractFixMessages": true,
    "bufferSize": 65536
  }
}
```

## Features

### File Discovery
- Recursively searches for files matching specified patterns
- Supports multiple file patterns (`out.dat`, `*.fix`, `*.log`)
- Maintains directory structure when downloading

### File Transfer
- Uses WinRM for secure remote access
- Transfers files as base64-encoded content
- Configurable buffer sizes for large files
- Retry logic with exponential backoff

### FIX Message Extraction
- Automatic detection of FIX messages (starts with `8=`)
- Parses common FIX tags (Symbol, Side, Quantity, Price, etc.)
- Supports different FIX delimiters (SOH, pipe, ^A)
- Exports to JSON and CSV formats

### Error Handling
- Connection testing before processing
- Comprehensive error logging
- Graceful handling of missing files
- Progress reporting

## Prerequisites

### PowerShell Requirements
- PowerShell 5.1 or later
- WinRM enabled on target servers
- Appropriate credentials for remote access

### Network Requirements
- Network connectivity to target servers
- WinRM ports open (default: 5985 for HTTP, 5986 for HTTPS)
- Firewall rules allowing WinRM traffic

### Permissions
- Read access to remote FIX log directories
- Local write permissions for output directory
- WinRM execution permissions on remote servers

## Setup Instructions

### 1. Enable WinRM on Remote Servers
```powershell
# On each remote server, run as Administrator:
winrm quickconfig
winrm set winrm/config/service '@{AllowUnencrypted="true"}'
winrm set winrm/config/service/auth '@{Basic="true"}'
```

### 2. Configure Trusted Hosts (if needed)
```powershell
# On client machine:
winrm set winrm/config/client '@{TrustedHosts="*"}'
# Or specify specific servers:
winrm set winrm/config/client '@{TrustedHosts="server1,server2,server3"}'
```

### 3. Test Connectivity
```powershell
Test-WSMan -ComputerName "your-server" -Credential $cred
```

## Usage Examples

### Basic File Fetching
```powershell
# Simple local fetch
.\simple-fix-file-fetcher.ps1 -RootPath "C:\fix_logs"

# Remote server with credentials
$cred = Get-Credential
.\simple-fix-file-fetcher.ps1 -RemoteComputer "PROD-FIX-01" -RootPath "D:\fix_logs" -Credential $cred
```

### Advanced Processing
```powershell
# Process all configured servers
.\advanced-fix-fetcher.ps1 -AllServers -Credential $cred

# Process specific date range
$yesterday = (Get-Date).AddDays(-1).ToString("yyyyMMdd")
.\fetch-remote-fix-files.ps1 -RemoteComputer "PROD-FIX-01" -RootPath "D:\fix_logs\$yesterday" -LocalOutputPath "C:\Analysis\$yesterday"
```

### Multiple Servers
```powershell
$servers = @("PROD-FIX-01", "PROD-FIX-02", "UAT-FIX-01")
$cred = Get-Credential

foreach ($server in $servers) {
    .\fetch-remote-fix-files.ps1 -RemoteComputer $server -RootPath "D:\fix_logs" -LocalOutputPath "C:\Analysis\$server" -Credential $cred
}
```

### Scheduled Processing
```powershell
# Create scheduled task for daily processing
$action = New-ScheduledTaskAction -Execute 'PowerShell.exe' -Argument '-File "C:\Scripts\fetch-remote-fix-files.ps1" -RemoteComputer "PROD-FIX-01" -RootPath "D:\fix_logs" -LocalOutputPath "C:\Analysis\Daily"'
$trigger = New-ScheduledTaskTrigger -Daily -At 2AM
Register-ScheduledTask -Action $action -Trigger $trigger -TaskName "Daily FIX File Fetch"
```

## Output Structure

```
LocalOutputPath/
├── ServerName/
│   ├── out.dat
│   ├── fix_messages.json
│   ├── fix_messages.csv
│   └── subdirectory/
│       └── more_files.dat
├── all_fix_messages.json
└── fix_messages_summary.csv
```

## FIX Message Format

Extracted FIX messages are saved with the following structure:

```json
{
  "SourceFile": "D:\\fix_logs\\out.dat",
  "SourceServer": "PROD-FIX-01", 
  "MessageType": "D",
  "MessageTypeDescription": "NewOrderSingle",
  "Symbol": "AAPL",
  "Side": "1",
  "OrderQty": "100",
  "Price": "150.50",
  "ClOrdID": "ORDER123",
  "SendingTime": "20231201-14:30:00",
  "RawMessage": "8=FIX.4.4|9=178|35=D|49=SENDER|56=TARGET...",
  "ParsedFields": {
    "BeginString": "FIX.4.4",
    "MsgType": "NewOrderSingle",
    "Symbol": "AAPL"
  }
}
```

## Troubleshooting

### Common Issues

1. **WinRM Connection Failed**
   - Check if WinRM is enabled on target server
   - Verify credentials
   - Test network connectivity

2. **Access Denied**
   - Ensure user has read permissions on remote directories
   - Check if account is in Remote Management Users group

3. **File Not Found**
   - Verify the root path exists on remote server
   - Check if files match the specified pattern

4. **Script Execution Policy**
   ```powershell
   Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
   ```

### Debug Mode
Add `-Verbose` to any script for detailed logging:
```powershell
.\fetch-remote-fix-files.ps1 -RemoteComputer "SERVER01" -RootPath "C:\fix_logs" -Verbose
```

## Security Considerations

1. **Credentials**: Use service accounts with minimal required permissions
2. **Network**: Use HTTPS for WinRM when possible
3. **Storage**: Secure local storage of downloaded files
4. **Logging**: Ensure sensitive data is masked in logs
5. **Cleanup**: Implement retention policies for downloaded files

## Performance Tips

1. **Buffer Size**: Adjust buffer size based on file sizes and network speed
2. **Parallel Processing**: Process multiple servers in parallel using jobs
3. **Filtering**: Use date ranges to limit file scope
4. **Compression**: Consider compressing large files during transfer
5. **Scheduling**: Run during off-peak hours to minimize impact

## Support

For issues or questions:
1. Check the troubleshooting section
2. Verify prerequisites are met
3. Test with simple scenarios first
4. Enable verbose logging for detailed diagnostics