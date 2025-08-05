#!/bin/bash

# Example script showing how to run the FIX Log Comparison application with overrides
# Use this when default database/server settings fail and you need to use backup systems

# Set script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_FILE="$SCRIPT_DIR/../target/fix-log-comparison-*.jar"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}FIX Log Comparison - Override Examples${NC}"
echo "====================================="
echo

# Example 1: Override primary database URL
echo -e "${YELLOW}Example 1: Override Primary Database URL${NC}"
echo "Use case: Primary database is down, use backup database"
echo "Command:"
echo "java -jar $JAR_FILE \\"
echo "  --comparison.primary.url=jdbc:oracle:thin:@backup-db-01:1521:XE \\"
echo "  --comparison.primary.username=backup_user \\"
echo "  --comparison.primary.password=backup_password"
echo

# Example 2: Override SFTP server
echo -e "${YELLOW}Example 2: Override SFTP Server${NC}"
echo "Use case: Main SFTP server is unreachable, use backup SFTP"
echo "Command:"
echo "java -jar $JAR_FILE \\"
echo "  --comparison.sftp.host=backup-sftp.company.com \\"
echo "  --comparison.sftp.port=2222 \\"
echo "  --comparison.sftp.username=backup_user"
echo

# Example 3: Override mail server
echo -e "${YELLOW}Example 3: Override Mail Server${NC}"
echo "Use case: Main mail server is down, use backup mail server"
echo "Command:"
echo "java -jar $JAR_FILE \\"
echo "  --spring.mail.host=backup-mail.company.com \\"
echo "  --spring.mail.port=587"
echo

# Example 4: Override multiple settings
echo -e "${YELLOW}Example 4: Override Multiple Settings (Full Disaster Recovery)${NC}"
echo "Use case: Multiple systems are down, use all backup systems"
echo "Command:"
echo "java -jar $JAR_FILE \\"
echo "  --comparison.primary.url=jdbc:oracle:thin:@backup-db-01:1521:XE \\"
echo "  --comparison.secondary.url=jdbc:oracle:thin:@backup-db-02:1521:XE \\"
echo "  --comparison.sftp.host=backup-sftp.company.com \\"
echo "  --spring.mail.host=backup-mail.company.com"
echo

# Example 5: Using environment variables instead
echo -e "${YELLOW}Example 5: Using Environment Variables${NC}"
echo "Use case: Set overrides via environment variables for automation"
echo "Commands:"
echo "export PRIMARY_DB_HOST=backup-db-01"
echo "export PRIMARY_DB_PORT=1521"
echo "export PRIMARY_DB_SID=XE"
echo "export SFTP_HOST=backup-sftp.company.com"
echo "export MAIL_HOST=backup-mail.company.com"
echo "java -jar $JAR_FILE"
echo

# Example 6: Run with specific date and overrides
echo -e "${YELLOW}Example 6: Specific Date with Overrides${NC}"
echo "Use case: Re-run comparison for specific date with backup systems"
echo "Command:"
echo "java -jar $JAR_FILE 20231201 \\"
echo "  --comparison.primary.url=jdbc:oracle:thin:@backup-db-01:1521:XE \\"
echo "  --comparison.sftp.host=backup-sftp.company.com"
echo

echo -e "${GREEN}Additional Tips:${NC}"
echo "1. You can combine command-line overrides with environment variables"
echo "2. Command-line arguments take precedence over environment variables"
echo "3. Use --help or -h to see all available override options"
echo "4. Check logs for 'CURRENT CONFIGURATION' to verify your overrides"
echo "5. Connection tests will run before the main process to validate settings"
echo

echo -e "${RED}Important Security Note:${NC}"
echo "Avoid putting passwords directly in command line arguments in production!"
echo "Use environment variables or configuration files for sensitive information."
echo