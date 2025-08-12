# Daily FIX EOD Reconciliation System

This page converts the existing README into a structured reference for MkDocs with the following sections:

1. Design specification
2. Functional specification
3. Production setup for daily runs
4. Operational runbook (outage handling and recovery)

---

See also: [Operational Runbook](operational-runbook.md) for actionable procedures.

## 1. Design Specification

### 1.1 Purpose and Scope
Performs daily end-of-day reconciliation between database execution records and FIX log messages to ensure data integrity for fulfilled and partial fulfilled orders.

### 1.2 Architecture Overview

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Database      │    │   SFTP Server   │    │   Email Server  │
│   (Oracle)      │    │   (FIX Logs)    │    │   (SMTP)        │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                    FIX EOD Reconciliation                        │
│                                                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │
│  │   Primary   │  │  Secondary  │  │   SFTP      │               │
│  │  Database   │  │  Database   │  │   Service   │               │
│  │ (TT_CUST_   │  │ (FIX_EXEC_  │  │             │               │
│  │   DONE)     │  │    RPT)     │  │             │               │
│  └─────────────┘  └─────────────┘  └─────────────┘               │
│                                                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │
│  │   FIX       │  │ Comparison  │  │   Excel     │               │
│  │  Parser     │  │  Service    │  │   Report    │               │
│  │             │  │             │  │   Service   │               │
│  └─────────────┘  └─────────────┘  └─────────────┘               │
│                                                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │
│  │   Email     │  │   Config    │  │   Main      │               │
│  │ Notification│  │ Management  │  │ Application │               │
│  │  Service    │  │             │  │             │               │
│  └─────────────┘  └─────────────┘  └─────────────┘               │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 Key Components
- Database access (Oracle): fetches fulfilled and partial fulfilled orders from `TT_CUST_DONE`.
- SFTP client: downloads FIX logs from remote servers.
- FIX parser: extracts messages (e.g., Tags 6, 14, 55, 54, 1).
- Comparison service: compares DB vs FIX fields (Price, Quantity, Symbol, Side, Account).
- Reporting service: generates Excel and sends email notifications.
- Configuration management: profiles and environment-based overrides.
- Logging: application logs for troubleshooting and auditing.

### 1.4 Data Flow
1. Query DB for EOD executions.
2. Download FIX logs from remote SFTP.
3. Parse FIX messages and index by identifiers (e.g., `EXEC_ID`).
4. Compare key fields and collect discrepancies.
5. Generate Excel report and email summary.

---

## 2. Functional Specification

### 2.1 Inputs
- Oracle DB credentials and URL
- SFTP server host, credentials, remote path
- Date parameter: defaults to yesterday; accepts `YYYYMMDD` or `YYYY-MM-DD`
- SMTP credentials and recipients

### 2.2 Processing Logic
- Filter: fulfilled and partial fulfilled orders only
- Field mapping:
  - Price: DB `DONE_PRICE` ↔ FIX Tag `6`
  - Quantity: DB `DONE_QTY` ↔ FIX Tag `14`
  - Symbol: DB `CONTRACT_CODE` ↔ FIX Tag `55`
  - Side: DB `BS_FLAG` ↔ FIX Tag `54`
  - Account: DB `CUST_NO` ↔ FIX Tag `1`
- Matching by `DONE_NO` and/or `EXEC_ID`
- Discrepancy classification: Missing in FIX, Missing in DB, Value mismatch

### 2.3 Outputs
- Email summary:
  - Subject: `[ENV] Daily FIX EOD Reconciliation Report - <No Discrepancies | Discrepancies Found>`
  - Attachment: Excel workbook with sheets: Summary, Discrepancy Details, Discrepancy Summary
- Logs: `logs/fix-comparison.log`

### 2.4 Error Handling
- Retries for SFTP download and DB connectivity
- Clear error messages with root cause
- Non-zero exit status on failure

---

## 3. Production Setup for Daily Run

### 3.1 Prerequisites
- Java 17+
- Network access to Oracle DB, SFTP, SMTP
- Appropriate credentials stored securely (e.g., environment variables, vault)

### 3.2 Configuration
Use environment variables or `application.yml`:

```yaml
comparison:
  primary:
    url: jdbc:oracle:thin:@${PRIMARY_DB_HOST}:1521:XE
    username: ${PRIMARY_DB_USER}
    password: ${PRIMARY_DB_PASSWORD}

  sftp:
    host: ${SFTP_HOST}
    username: ${SFTP_USER}
    password: ${SFTP_PASSWORD}
    remote-directory: /fix_logs

  email:
    from: fix-comparison@company.com
    to: [trading-ops@company.com, risk-management@company.com]
    subject: "Daily FIX EOD Reconciliation Report"
```

Profiles: `dev`, `test`, `docker`, `prod` via `--spring.profiles.active`.

### 3.3 Build and Deploy
```bash
mvn clean package
```
Produce `target/fix-log-comparison.jar` and deploy to the scheduler host.

### 3.4 Scheduling
- Cron, Windows Task Scheduler, or enterprise scheduler
- Daily run after EOD (e.g., 02:00 local)
- Command examples:
```bash
# Default (yesterday)
java -jar target/fix-log-comparison.jar

# Specific date
java -jar target/fix-log-comparison.jar 20241201
java -jar target/fix-log-comparison.jar 2024-12-01

# With profile
java -jar target/fix-log-comparison.jar --spring.profiles.active=prod
```

---

## 4. Support
- Technical issues: it-support@company.com
- Trading operations: trading-ops@company.com
- Risk management: risk-management@company.com
