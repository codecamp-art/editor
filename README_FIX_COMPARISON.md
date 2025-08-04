# FIX Log Comparison System

A comprehensive Spring Boot command-line application for comparing database records (TT_CUST_DONE table) with FIX log files from remote servers.

## Features

- **Database Integration**: Connects to multiple Oracle databases
- **SFTP Support**: Fetches FIX log files from remote Windows servers
- **FIX Message Parsing**: Parses and validates FIX protocol messages
- **Data Comparison**: Compares database records with FIX messages across multiple fields
- **Excel Reporting**: Generates detailed Excel reports with discrepancy analysis
- **Email Notifications**: Sends automated email notifications with summary and attachments
- **High Performance**: Multi-threaded processing for optimal performance
- **Robust Error Handling**: Comprehensive error handling and logging

## Architecture

### Package Structure
```
org.example.comparison/
├── domain/                    # Domain models (DDD)
│   ├── TtCustDone.java       # Database table model
│   ├── FixExecRpt.java       # FIX execution report model
│   ├── FixMessage.java       # Parsed FIX message model
│   └── ComparisonResult.java # Comparison result model
├── repository/               # Data access layer
│   ├── TtCustDoneRepository.java
│   └── FixExecRptRepository.java
├── service/                  # Business logic layer
│   ├── ComparisonService.java
│   ├── FixMessageParser.java
│   ├── SftpService.java
│   ├── ExcelReportService.java
│   └── EmailNotificationService.java
├── config/                   # Configuration classes
│   ├── ComparisonConfig.java
│   └── DatabaseConfig.java
└── FixLogComparisonApplication.java
```

### Comparison Logic

1. **Data Retrieval**
   - Fetch TT_CUST_DONE records for the specified date
   - Retrieve related FIX_EXEC_RPT records using DONE_NO
   - Download FIX log files from remote SFTP servers

2. **Message Parsing**
   - Parse FIX messages from log files
   - Extract relevant tags (1, 6, 14, 17, 54, 55)
   - Validate message completeness

3. **Comparison**
   - Match records using DONE_NO → EXECID relationship
   - Compare field values:
     - DONE_PRICE ↔ Tag 6 (Average Price)
     - DONE_QTY ↔ Tag 14 (Cumulative Quantity)
     - CONTRACT_CODE ↔ Tag 55 (Symbol)
     - BS_FLAG ↔ Tag 54 (Side)
     - CUST_NO ↔ Tag 1 (Account)

4. **Reporting**
   - Generate Excel report with discrepancy details
   - Send email notifications with summary

## Configuration

### Database Configuration
```yaml
comparison:
  primary:  # TT_CUST_DONE database
    url: jdbc:oracle:thin:@host:port:sid
    username: ${PRIMARY_DB_USER}
    password: ${PRIMARY_DB_PASSWORD}
    driver-class-name: oracle.jdbc.OracleDriver
  
  secondary:  # FIX_EXEC_RPT database
    url: jdbc:oracle:thin:@host:port:sid
    username: ${SECONDARY_DB_USER}
    password: ${SECONDARY_DB_PASSWORD}
    driver-class-name: oracle.jdbc.OracleDriver
```

### SFTP Configuration
```yaml
comparison:
  sftp:
    host: ${SFTP_HOST}
    port: 22
    username: ${SFTP_USER}
    password: ${SFTP_PASSWORD}
    remote-directory: /fix_logs
    connection-timeout: 30000
    session-timeout: 30000
```

### Email Configuration
```yaml
comparison:
  email:
    from: fix-comparison@company.com
    to:
      - trading-ops@company.com
      - risk-management@company.com
    cc:
      - it-support@company.com
    subject: "Daily FIX Log Comparison Report"
```

## Running the Application

### Command Line Options

```bash
# Run with default date (yesterday)
java -jar fix-log-comparison.jar

# Run with specific date (yyyyMMdd format)
java -jar fix-log-comparison.jar 20231201

# Run with specific date (yyyy-MM-dd format)
java -jar fix-log-comparison.jar 2023-12-01
```

### Environment Variables

Set the following environment variables for secure configuration:

```bash
# Database Configuration
export PRIMARY_DB_HOST=primary-db-server
export PRIMARY_DB_USER=primary_user
export PRIMARY_DB_PASSWORD=primary_password
export SECONDARY_DB_HOST=secondary-db-server
export SECONDARY_DB_USER=secondary_user
export SECONDARY_DB_PASSWORD=secondary_password

# SFTP Configuration
export SFTP_HOST=sftp-server
export SFTP_USER=sftp_user
export SFTP_PASSWORD=sftp_password

# Email Configuration
export MAIL_USERNAME=smtp_user
export MAIL_PASSWORD=smtp_password
```

### Build and Package

```bash
# Clean and package
mvn clean package

# Skip tests for faster build
mvn clean package -DskipTests

# Run tests only
mvn test
```

## FIX Message Format

The application supports FIX messages in the standard FIX protocol format with SOH separators:

```
778              34=778\u000152=20250801-06:28:25.779\u000135=8\u000149=QA_Client_XX\u000156=QA_Server_XX\u000157=QFNServer\u000131=0.0000\u000132=0.0000\u00016=0.0000\u000138=1.0000\u0001151=0.0000\u000114=0.0000\u000111=5080106282561837t7k8\u000137=0000013\u000117=00000130\u000154=1\u000177=O\u000139=8\u0001150=8\u000120=0\u000160=20250801-06:28:25.779\u0001207=XDCE\u000140=1\u000159=0\u00011=10000\u0001103=0\u000115=CNY\u000199=0000\u0001110=0\u0001779              ...
```

### Format Structure
- **Sequence Number**: Leading number (778, 779, 780, etc.) followed by spaces
- **FIX Fields**: Standard tag=value pairs separated by SOH (\u0001) characters
- **Multiple Messages**: Multiple FIX messages in a single log file
- **Standard Compliance**: Follows FIX protocol specification with proper field separators

### Key FIX Tags
- **Tag 1**: Account (Customer Number)
- **Tag 6**: Average Price
- **Tag 14**: Cumulative Quantity
- **Tag 17**: Execution ID
- **Tag 35**: Message Type (8 = Execution Report)
- **Tag 54**: Side (1=Buy, 2=Sell)
- **Tag 55**: Symbol (Contract Code)
- **Tag 52**: Sending Time
- **Tag 60**: Transaction Time

### Parser Features
- **SOH-Aware Parsing**: Primary support for standard FIX SOH (\u0001) field separators
- **Robust Tag Extraction**: Handles various field separator formats with automatic fallback
- **Standard Compliance**: Fully compliant with FIX protocol message structure
- **Sequence Number Support**: Extracts and tracks sequence numbers from log format
- **Multiple Message Support**: Parses multiple FIX messages from single log file
- **Backward Compatibility**: Supports legacy formats (pipe-separated, concatenated) as fallback

## Output

### Excel Report
The application generates detailed Excel reports with multiple sheets:

1. **Summary Sheet**: Overview of comparison results
2. **Discrepancy Details**: Line-by-line discrepancy information
3. **Discrepancy Summary**: Aggregated statistics by type and field

### Email Notifications

#### With Discrepancies
- **Subject**: "Daily FIX Log Comparison Report - Discrepancies Found"
- **Content**: HTML table with summary and sample discrepancies
- **Attachment**: Excel report with full details

#### No Discrepancies
- **Subject**: "Daily FIX Log Comparison Report - No Discrepancies"
- **Content**: Summary confirming all records match

## Error Handling

- **Database Connectivity**: Automatic retry with connection pooling
- **SFTP Issues**: Individual file failures don't stop the entire process
- **Email Failures**: Logged with detailed error information
- **Parsing Errors**: Invalid FIX messages are logged but don't halt processing

## Performance Considerations

- **Multi-threading**: Parallel processing of log files and message parsing
- **Connection Pooling**: HikariCP for optimal database performance
- **Memory Management**: Streaming approach for large log files
- **Caching**: Efficient data structures for quick lookups

## Monitoring and Logging

- **Application Logs**: Comprehensive logging with configurable levels
- **Performance Metrics**: Timing information for each processing stage
- **Health Checks**: Built-in Spring Actuator endpoints

## Security

- **Credential Management**: Environment variable-based configuration
- **SFTP Security**: SSH key authentication support (configurable)
- **Database Security**: Encrypted connections with SSL support

## Testing

The application includes comprehensive test coverage:

- **Unit Tests**: Individual component testing
- **Integration Tests**: End-to-end workflow testing
- **Repository Tests**: Database interaction testing
- **Service Tests**: Business logic verification

Run tests:
```bash
mvn test
```

## Deployment

### Production Environment
1. Set up Oracle databases with required tables
2. Configure SFTP access to FIX log servers
3. Set up SMTP server for email notifications
4. Deploy as a scheduled service or cron job

### Example Cron Configuration
```bash
# Run daily at 8:00 AM
0 8 * * * /opt/fix-comparison/run-comparison.sh
```

## Troubleshooting

### Common Issues

1. **Database Connection Failures**
   - Verify connection strings and credentials
   - Check network connectivity and firewall rules
   - Ensure Oracle JDBC driver is available

2. **SFTP Connection Issues**
   - Verify SFTP server accessibility
   - Check username/password or SSH key configuration
   - Ensure remote directory exists and is accessible

3. **Email Delivery Problems**
   - Verify SMTP server configuration
   - Check email addresses and authentication
   - Review firewall rules for SMTP ports

4. **FIX Parsing Errors**
   - Verify FIX message format matches expected structure
   - Check for proper field separators and encoding
   - Review log files for specific parsing errors

### Log Analysis
Check application logs for detailed error information:
```bash
tail -f logs/fix-comparison.log
```

## Support

For technical support or feature requests, please contact:
- Development Team: dev-team@company.com
- Operations Team: ops-team@company.com