# Daily FIX EOD Reconciliation System

A Spring Boot application that performs daily end-of-day reconciliation between database records and FIX log messages for **fulfilled and partial fulfilled orders**.

## Purpose

This system ensures data integrity by comparing trading execution records stored in the database against the original FIX messages from trading systems. It helps identify discrepancies that could indicate data synchronization issues, system problems, or reconciliation gaps.

## What It Does

1. **Fetches Database Records**: Retrieves fulfilled and partial fulfilled orders from TT_CUST_DONE table
2. **Downloads FIX Logs**: Securely downloads FIX log files from remote servers via SFTP
3. **Parses FIX Messages**: Extracts and validates FIX protocol messages from log files
4. **Performs Comparison**: Compares database records with FIX messages across key fields:
   - **Price**: Database DONE_PRICE vs FIX Tag 6 (Average Price)
   - **Quantity**: Database DONE_QTY vs FIX Tag 14 (Cumulative Quantity)
   - **Symbol**: Database CONTRACT_CODE vs FIX Tag 55 (Symbol)
   - **Side**: Database BS_FLAG vs FIX Tag 54 (Buy/Sell)
   - **Account**: Database CUST_NO vs FIX Tag 1 (Account)
5. **Generates Reports**: Creates detailed Excel reports and sends email notifications

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Database      │    │   SFTP Server   │    │   Email Server  │
│   (Oracle)      │    │   (FIX Logs)    │    │   (SMTP)        │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                    FIX EOD Reconciliation                      │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐           │
│  │   Primary   │  │  Secondary  │  │   SFTP      │           │
│  │  Database   │  │  Database   │  │   Service   │           │
│  │ (TT_CUST_   │  │ (FIX_EXEC_  │  │             │           │
│  │   DONE)     │  │    RPT)     │  │             │           │
│  └─────────────┘  └─────────────┘  └─────────────┘           │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐           │
│  │   FIX       │  │ Comparison  │  │   Excel     │           │
│  │  Parser     │  │  Service    │  │   Report    │           │
│  │             │  │             │  │   Service   │           │
│  └─────────────┘  └─────────────┘  └─────────────┘           │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐           │
│  │   Email     │  │   Config    │  │   Main      │           │
│  │ Notification│  │ Management  │  │ Application │           │
│  │  Service    │  │             │  │             │           │
│  └─────────────┘  └─────────────┘  └─────────────┘           │
└─────────────────────────────────────────────────────────────────┘
```

## How to Run

### Prerequisites
- Java 17 or higher
- Oracle database access
- SFTP server access for FIX logs
- SMTP server for email notifications

### Quick Start

1. **Configure Environment Variables**:
```bash
# Database Configuration
export PRIMARY_DB_HOST=your-primary-db-server
export PRIMARY_DB_USER=your_username
export PRIMARY_DB_PASSWORD=your_password

export SECONDARY_DB_HOST=your-secondary-db-server
export SECONDARY_DB_USER=your_username
export SECONDARY_DB_PASSWORD=your_password

# SFTP Configuration
export SFTP_HOST=your-sftp-server
export SFTP_USER=your_sftp_user
export SFTP_PASSWORD=your_sftp_password

# Email Configuration
export MAIL_USERNAME=your_smtp_user
export MAIL_PASSWORD=your_smtp_password
```

2. **Build the Application**:
```bash
mvn clean package
```

3. **Run the Reconciliation**:
```bash
# Run for yesterday's data
java -jar target/fix-log-comparison.jar

# Run for specific date (YYYYMMDD format)
java -jar target/fix-log-comparison.jar 20241201

# Run for specific date (YYYY-MM-DD format)
java -jar target/fix-log-comparison.jar 2024-12-01
```

### Environment-Specific Configuration

The application supports different environments with automatic environment detection:

- **Development**: `[DEV] Daily FIX EOD Reconciliation Report`
- **Test**: `[TEST] Daily FIX EOD Reconciliation Report`
- **Docker**: `[DOCKER] Daily FIX EOD Reconciliation Report`
- **Production**: `Daily FIX EOD Reconciliation Report`

Run with specific profile:
```bash
java -jar target/fix-log-comparison.jar --spring.profiles.active=dev
```

## Output and Reports

### Email Notifications

#### When Discrepancies Are Found
- **Subject**: `[ENV] Daily FIX EOD Reconciliation Report - Discrepancies Found`
- **Content**: 
  - Summary of comparison results
  - Breakdown of discrepancies by type and field
  - Sample discrepancies (first 10)
  - Action required message
- **Attachment**: Detailed Excel report

#### When No Discrepancies Are Found
- **Subject**: `[ENV] Daily FIX EOD Reconciliation Report - No Discrepancies`
- **Content**: 
  - Confirmation that all fulfilled and partial fulfilled orders match
  - Summary statistics
  - No action required message

### Excel Report Structure

The Excel report contains multiple sheets with comprehensive analysis:

#### 1. Summary Sheet
- **Total Records**: Number of database records processed
- **Total FIX Messages**: Number of FIX messages parsed
- **Total Discrepancies**: Number of mismatches found
- **Comparison Date**: Date of the reconciliation
- **Processing Time**: How long the reconciliation took

#### 2. Discrepancy Details Sheet
Detailed line-by-line breakdown of each discrepancy:

| Column | Description |
|--------|-------------|
| DONE_NO | Database record identifier |
| EXEC_ID | FIX execution ID |
| Field | Field name that has discrepancy |
| DB Value | Value from database |
| FIX Value | Value from FIX message |
| Discrepancy Type | Type of mismatch (Missing, Different, etc.) |

#### 3. Discrepancy Summary Sheet
Aggregated statistics by:
- **Discrepancy Type**: Missing records, different values, etc.
- **Field**: Which fields have the most issues
- **Severity**: Impact level of discrepancies

## How to Read the Report

### 1. Check Email Subject
- **Green/No Issues**: Subject indicates "No Discrepancies" - everything is good
- **Red/Issues Found**: Subject indicates "Discrepancies Found" - review required

### 2. Review Email Summary
- **Total Records**: Should match expected daily volume
- **Total Discrepancies**: Zero is ideal, any number > 0 needs investigation
- **Breakdown by Type**: Helps identify patterns (e.g., all missing records vs data differences)

### 3. Analyze Excel Report
1. **Start with Summary Sheet**: Get overall picture
2. **Review Discrepancy Details**: 
   - Look for patterns in specific fields
   - Check if issues are isolated or systemic
   - Identify specific DONE_NO/EXEC_ID combinations
3. **Use Discrepancy Summary**: 
   - Focus on fields with highest discrepancy counts
   - Prioritize by discrepancy type severity

### 4. Common Discrepancy Types

| Type | Description | Action |
|------|-------------|--------|
| **Missing in FIX** | Database record exists but no matching FIX message | Check if FIX log file is complete |
| **Missing in DB** | FIX message exists but no database record | Verify database synchronization |
| **Price Mismatch** | Different prices between DB and FIX | Investigate pricing logic |
| **Quantity Mismatch** | Different quantities between DB and FIX | Check quantity calculations |
| **Symbol Mismatch** | Different contract codes | Verify symbol mapping |

### 5. Investigation Steps

1. **Isolate the Issue**: Focus on specific DONE_NO/EXEC_ID combinations
2. **Check Data Sources**: Verify both database and FIX log data independently
3. **Review Timing**: Ensure comparing data from same time period
4. **System Check**: Verify all systems are functioning normally
5. **Escalate**: Contact trading operations if discrepancies are significant

## Configuration Files

### application.yml (Production)
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

### Environment-Specific Overrides
- `application-dev.yml`: Development environment settings
- `application-test.yml`: Test environment settings  
- `application-docker.yml`: Docker environment settings

## Troubleshooting

### Common Issues

| Issue | Symptoms | Solution |
|-------|----------|----------|
| **Database Connection** | Connection timeout errors | Verify database credentials and network connectivity |
| **SFTP Issues** | File download failures | Check SFTP credentials and server accessibility |
| **Email Delivery** | Email not received | Verify SMTP configuration and firewall settings |
| **FIX Parsing** | Parsing errors in logs | Check FIX message format and file encoding |

### Log Analysis
```bash
# Check application logs
tail -f logs/fix-comparison.log

# Look for specific errors
grep "ERROR" logs/fix-comparison.log

# Check processing statistics
grep "Total" logs/fix-comparison.log
```

## Support

- **Technical Issues**: Contact IT Support (it-support@company.com)
- **Trading Operations**: Contact Trading Ops (trading-ops@company.com)
- **Risk Management**: Contact Risk Team (risk-management@company.com)

---

**Note**: This system only compares **fulfilled and partial fulfilled orders**. Other order types are excluded from the reconciliation process.