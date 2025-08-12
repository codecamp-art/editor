# Operational Runbook: FIX EOD Reconciliation

This runbook provides actionable procedures for operations teams to handle outages, failovers, and recovery. It assumes the application artifact is `fix-log-comparison.jar` and logs to `logs/fix-comparison.log`.

Use the left navigation to return to Home or the main spec page.

---

## 1. Quick Reference

- Primary commands
```bash
# Default (process yesterday)
java -jar fix-log-comparison.jar

# Specific date
java -jar fix-log-comparison.jar 2024-12-01
java -jar fix-log-comparison.jar 20241201

# With overrides (examples below)
java -jar fix-log-comparison.jar --comparison.sftp.host=backup-sftp.company.com
```

- Log file: `logs/fix-comparison.log`
- Return code: non-zero indicates failure

---

## 2. Switch Remote SFTP Server

### 2.1 Linux/macOS

- Temporary override (one run):
```bash
java -jar fix-log-comparison.jar \
  --comparison.sftp.host=backup-sftp.company.com \
  --comparison.sftp.port=22
```

- Permanent (session) override:
```bash
export SFTP_HOST=backup-sftp.company.com
export SFTP_PORT=22
java -jar fix-log-comparison.jar
```

- Connectivity checks:
```bash
nc -vz "$SFTP_HOST" 22 || ssh -o BatchMode=yes "$SFTP_USER"@"$SFTP_HOST" exit
```

### 2.2 Windows (PowerShell)

- Temporary override:
```powershell
java -jar fix-log-comparison.jar --comparison.sftp.host=backup-sftp.company.com --comparison.sftp.port=22
```

- Permanent (session) override:
```powershell
$env:SFTP_HOST = 'backup-sftp.company.com'
$env:SFTP_PORT = '22'
java -jar fix-log-comparison.jar
```

- Connectivity checks:
```powershell
Test-NetConnection $env:SFTP_HOST -Port 22 | Format-List
# Or using SSH client if available
ssh -o BatchMode=yes "$env:SFTP_USER@$env:SFTP_HOST" exit
```

- If using config files, modify `comparison.sftp.host` in your override file and run with:
```powershell
java -jar fix-log-comparison.jar --spring.config.additional-location=file:./override.properties
```

---

## 3. Switch Database URL

### 3.1 Linux/macOS

- Override JDBC URL for the run:
```bash
java -jar fix-log-comparison.jar \
  --comparison.primary.url=jdbc:oracle:thin:@backup-db-01:1521:XE \
  --comparison.primary.username=backup_user
```

- Environment variables approach:
```bash
export PRIMARY_DB_URL=jdbc:oracle:thin:@backup-db-01:1521:XE
export PRIMARY_DB_USER=backup_user
export PRIMARY_DB_PASSWORD=********
java -jar fix-log-comparison.jar
```

- Validate connectivity (examples):
```bash
# If sqlplus is available
sqlplus "$PRIMARY_DB_USER/$PRIMARY_DB_PASSWORD@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=backup-db-01)(PORT=1521))(CONNECT_DATA=(SID=XE)))" @check.sql
```

### 3.2 Windows (PowerShell)

- Command-line override:
```powershell
java -jar fix-log-comparison.jar --comparison.primary.url=jdbc:oracle:thin:@backup-db-01:1521:XE --comparison.primary.username=backup_user
```

- Environment variables:
```powershell
$env:PRIMARY_DB_URL = 'jdbc:oracle:thin:@backup-db-01:1521:XE'
$env:PRIMARY_DB_USER = 'backup_user'
$env:PRIMARY_DB_PASSWORD = '********'
java -jar fix-log-comparison.jar
```

- Validate connectivity using vendor tools (e.g., SQL*Plus) if present.

- If using config files, modify `comparison.primary.url` and run with:
```powershell
java -jar fix-log-comparison.jar --spring.config.additional-location=file:./override.properties
```

---

## 4. Rerun the Job After Failure

1. Identify the failed date:
   - Check scheduler history (Cron, Windows Task Scheduler)
   - Review `logs/fix-comparison.log`
2. Re-run with explicit date:
```bash
java -jar fix-log-comparison.jar 2024-12-01
# or
java -jar fix-log-comparison.jar 20241201
```
3. Optional: run with DR overrides if needed (combine with sections 2–3).
4. Verify success from exit code and email/Excel report presence.

- Idempotency: subsequent runs for the same date overwrite outputs.

---

## 5. Logs: Location and Inspection

- Application logs:
  - Path: `logs/fix-comparison.log`
  - Rotation/retention: per deployment configuration

### 5.1 Linux/macOS
```bash
# Live tail
tail -f logs/fix-comparison.log

# Error search
grep -i "error" logs/fix-comparison.log | tail -50

# Job run summary
grep -i "total" logs/fix-comparison.log | tail -20
```

### 5.2 Windows (PowerShell)
```powershell
Get-Content .\logs\fix-comparison.log -Wait
Select-String -Path .\logs\fix-comparison.log -Pattern 'ERROR' | Select-Object -Last 50
Select-String -Path .\logs\fix-comparison.log -Pattern 'Total' | Select-Object -Last 20
```

- At application startup, verify the "CURRENT CONFIGURATION" block to confirm overrides applied.

---

## 6. Common Issues and Playbooks

### 6.1 Database Connection Failures
- Symptoms: timeouts, ORA- errors
- Actions:
  - Verify `comparison.primary.url` or `PRIMARY_DB_URL`
  - Check credentials work independently
  - Network/firewall routes to backup DB
  - Retry with command-line override

### 6.2 SFTP Failures
- Symptoms: connection refused, auth errors, file not found
- Actions:
  - Verify `comparison.sftp.host` or `SFTP_HOST`
  - Confirm port and credentials
  - Validate remote path `comparison.sftp.remote-directory`
  - Test with `ssh`/`sftp` client

### 6.3 Email Delivery Issues
- Symptoms: no notification email
- Actions:
  - Verify `spring.mail.*` settings or environment variables
  - Check SMTP connectivity and auth
  - Review application logs for mail errors

### 6.4 FIX Parsing Errors
- Symptoms: malformed message errors
- Actions:
  - Confirm file delimiter (SOH vs `|`)
  - Ensure the correct logs for the date range were fetched
  - Re-run parsing with verbose logging if available

---

## 7. Override Methods Reference

See also: `CONFIGURATION_OVERRIDE_GUIDE.md`.

- Command-line (highest priority):
```bash
java -jar fix-log-comparison.jar \
  --comparison.primary.url=jdbc:oracle:thin:@backup-db:1521:XE \
  --comparison.sftp.host=backup-sftp.company.com
```

- Environment variables:
```bash
export PRIMARY_DB_URL=jdbc:oracle:thin:@backup-db:1521:XE
export SFTP_HOST=backup-sftp.company.com
```

- Custom file:
```bash
java -jar fix-log-comparison.jar --spring.config.additional-location=file:./override.properties
```

---

## 8. Verification Checklist (Post-Change)

- [ ] Application started without errors
- [ ] Logged "CURRENT CONFIGURATION" shows intended overrides
- [ ] SFTP connectivity verified to target host
- [ ] DB connectivity verified (if possible) to target host
- [ ] Email summary received
- [ ] Excel report generated for target date
