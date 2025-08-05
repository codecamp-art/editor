# Configuration Override Guide

This guide explains how to override database URLs and server hostnames when default settings fail.

## Overview

The FIX Log Comparison application supports flexible configuration overrides to handle situations where default database or server settings are unavailable. This is essential for disaster recovery scenarios or when primary systems are under maintenance.

## Override Methods

### 1. Command Line Arguments (Recommended)

Spring Boot automatically supports property overrides via command-line arguments using the `--property.name=value` syntax.

#### Database URL Overrides

```bash
# Override primary database URL
java -jar fix-log-comparison.jar \
  --comparison.primary.url=jdbc:oracle:thin:@backup-db:1521:XE

# Override secondary database URL  
java -jar fix-log-comparison.jar \
  --comparison.secondary.url=jdbc:oracle:thin:@backup-db:1521:XE

# Override both databases
java -jar fix-log-comparison.jar \
  --comparison.primary.url=jdbc:oracle:thin:@backup-db-01:1521:XE \
  --comparison.secondary.url=jdbc:oracle:thin:@backup-db-02:1521:XE
```

#### Server Hostname Overrides

```bash
# Override SFTP server hostname
java -jar fix-log-comparison.jar \
  --comparison.sftp.host=backup-sftp.company.com

# Override mail server hostname
java -jar fix-log-comparison.jar \
  --spring.mail.host=backup-mail.company.com

# Override SFTP port as well
java -jar fix-log-comparison.jar \
  --comparison.sftp.host=backup-sftp.company.com \
  --comparison.sftp.port=2222
```

#### Complete Override Example

```bash
# Full disaster recovery scenario
java -jar fix-log-comparison.jar 20231201 \
  --comparison.primary.url=jdbc:oracle:thin:@backup-db-01:1521:XE \
  --comparison.primary.username=backup_user \
  --comparison.secondary.url=jdbc:oracle:thin:@backup-db-02:1521:XE \
  --comparison.sftp.host=backup-sftp.company.com \
  --spring.mail.host=backup-mail.company.com
```

### 2. Environment Variables

Set environment variables before running the application:

```bash
# Database settings
export PRIMARY_DB_HOST=backup-db-01
export PRIMARY_DB_PORT=1521
export PRIMARY_DB_SID=XE
export PRIMARY_DB_USER=backup_user
export PRIMARY_DB_PASSWORD=backup_password

# Alternative: Full URL override
export PRIMARY_DB_URL=jdbc:oracle:thin:@backup-db-01:1521:XE

# SFTP settings
export SFTP_HOST=backup-sftp.company.com
export SFTP_PORT=2222
export SFTP_USER=backup_user
export SFTP_PASSWORD=backup_password

# Mail settings
export MAIL_HOST=backup-mail.company.com
export MAIL_PORT=587

# Run application
java -jar fix-log-comparison.jar
```

### 3. Custom Configuration Files

Create a custom application properties file:

```properties
# override.properties
comparison.primary.url=jdbc:oracle:thin:@backup-db:1521:XE
comparison.sftp.host=backup-sftp.company.com
spring.mail.host=backup-mail.company.com
```

Run with custom configuration:

```bash
java -jar fix-log-comparison.jar \
  --spring.config.additional-location=file:./override.properties
```

## Available Override Properties

### Database Configuration

| Property | Description | Example |
|----------|-------------|---------|
| `comparison.primary.url` | Primary database JDBC URL | `jdbc:oracle:thin:@backup-db:1521:XE` |
| `comparison.primary.username` | Primary database username | `backup_user` |
| `comparison.primary.password` | Primary database password | `backup_password` |
| `comparison.secondary.url` | Secondary database JDBC URL | `jdbc:oracle:thin:@backup-db:1521:XE` |
| `comparison.secondary.username` | Secondary database username | `backup_user` |
| `comparison.secondary.password` | Secondary database password | `backup_password` |

### SFTP Configuration

| Property | Description | Example |
|----------|-------------|---------|
| `comparison.sftp.host` | SFTP server hostname | `backup-sftp.company.com` |
| `comparison.sftp.port` | SFTP server port | `2222` |
| `comparison.sftp.username` | SFTP username | `backup_user` |
| `comparison.sftp.password` | SFTP password | `backup_password` |
| `comparison.sftp.remote-directory` | Remote directory path | `/backup/fix_logs` |

### Email Configuration

| Property | Description | Example |
|----------|-------------|---------|
| `spring.mail.host` | Mail server hostname | `backup-mail.company.com` |
| `spring.mail.port` | Mail server port | `587` |
| `spring.mail.username` | Mail username | `backup_user` |
| `spring.mail.password` | Mail password | `backup_password` |

### Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `PRIMARY_DB_HOST` | Primary database host | `backup-db-01` |
| `PRIMARY_DB_PORT` | Primary database port | `1521` |
| `PRIMARY_DB_SID` | Primary database SID | `XE` |
| `PRIMARY_DB_USER` | Primary database user | `backup_user` |
| `PRIMARY_DB_PASSWORD` | Primary database password | `backup_password` |
| `PRIMARY_DB_URL` | Complete primary DB URL | `jdbc:oracle:thin:@backup-db:1521:XE` |
| `SECONDARY_DB_HOST` | Secondary database host | `backup-db-02` |
| `SECONDARY_DB_PORT` | Secondary database port | `1521` |
| `SECONDARY_DB_SID` | Secondary database SID | `XE` |
| `SECONDARY_DB_USER` | Secondary database user | `backup_user` |
| `SECONDARY_DB_PASSWORD` | Secondary database password | `backup_password` |
| `SECONDARY_DB_URL` | Complete secondary DB URL | `jdbc:oracle:thin:@backup-db:1521:XE` |
| `SFTP_HOST` | SFTP server hostname | `backup-sftp.company.com` |
| `SFTP_PORT` | SFTP server port | `2222` |
| `SFTP_USER` | SFTP username | `backup_user` |
| `SFTP_PASSWORD` | SFTP password | `backup_password` |
| `SFTP_REMOTE_DIR` | SFTP remote directory | `/backup/fix_logs` |
| `MAIL_HOST` | Mail server hostname | `backup-mail.company.com` |
| `MAIL_PORT` | Mail server port | `587` |
| `MAIL_USERNAME` | Mail username | `backup_user` |
| `MAIL_PASSWORD` | Mail password | `backup_password` |

## Priority Order

Configuration values are resolved in the following priority order (highest to lowest):

1. **Command line arguments** (`--property.name=value`)
2. **Environment variables** (`VARIABLE_NAME`)
3. **Configuration files** (application-{profile}.yml)
4. **Default values** (in application.yml)

## Verification

The application logs the current configuration at startup. Look for the "CURRENT CONFIGURATION" section in the logs to verify your overrides are applied correctly:

```
=== CURRENT CONFIGURATION ===
Primary Database:
  URL: jdbc:oracle:thin:@backup-db:1521:XE
  Username: backup_user
  Driver: oracle.jdbc.OracleDriver
SFTP Configuration:
  Host: backup-sftp.company.com
  Port: 2222
  Username: backup_user
=============================
```

## Security Best Practices

1. **Avoid passwords in command line**: Use environment variables for sensitive information
2. **Use configuration files**: For complex overrides, use separate configuration files
3. **Secure storage**: Store override configuration files securely
4. **Audit logging**: Monitor when overrides are used

## Troubleshooting

### Connection Test Failures

If connection tests fail after applying overrides:

1. Verify the override values in the configuration log
2. Check network connectivity to backup systems
3. Validate credentials for backup systems
4. Ensure backup systems are operational

### Override Not Applied

If your override doesn't seem to take effect:

1. Check the property name spelling
2. Verify the priority order (command line beats environment variables)
3. Look for typos in environment variable names
4. Check the configuration log output

### Help Command

Use the help command to see all available options:

```bash
java -jar fix-log-comparison.jar --help
```

## Common Scenarios

### Scenario 1: Primary Database Down

```bash
java -jar fix-log-comparison.jar \
  --comparison.primary.url=jdbc:oracle:thin:@backup-db:1521:XE \
  --comparison.primary.username=backup_user
```

### Scenario 2: SFTP Server Unreachable

```bash
java -jar fix-log-comparison.jar \
  --comparison.sftp.host=backup-sftp.company.com \
  --comparison.sftp.port=2222
```

### Scenario 3: Complete System Failover

```bash
java -jar fix-log-comparison.jar \
  --comparison.primary.url=jdbc:oracle:thin:@dr-db-01:1521:XE \
  --comparison.secondary.url=jdbc:oracle:thin:@dr-db-02:1521:XE \
  --comparison.sftp.host=dr-sftp.company.com \
  --spring.mail.host=dr-mail.company.com
```

## Automation Scripts

See `scripts/run-with-overrides-example.sh` for example automation scripts that demonstrate various override scenarios.