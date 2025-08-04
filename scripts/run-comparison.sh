#!/bin/bash

# FIX Log Comparison Runner Script
# This script runs the FIX log comparison application with proper error handling and logging

# Configuration
APP_HOME="/opt/fix-comparison"
JAR_FILE="$APP_HOME/fix-log-comparison.jar"
LOG_DIR="$APP_HOME/logs"
CONFIG_FILE="$APP_HOME/application.yml"
PID_FILE="$APP_HOME/fix-comparison.pid"

# Create log directory if it doesn't exist
mkdir -p "$LOG_DIR"

# Function to log messages
log_message() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" | tee -a "$LOG_DIR/runner.log"
}

# Function to check if application is already running
check_running() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if ps -p "$pid" > /dev/null 2>&1; then
            log_message "ERROR: Application is already running with PID $pid"
            exit 1
        else
            log_message "WARNING: Stale PID file found, removing it"
            rm -f "$PID_FILE"
        fi
    fi
}

# Function to clean up on exit
cleanup() {
    log_message "Cleaning up..."
    if [ -f "$PID_FILE" ]; then
        rm -f "$PID_FILE"
    fi
}

# Set up signal handlers
trap cleanup EXIT
trap cleanup SIGINT
trap cleanup SIGTERM

# Main execution
main() {
    log_message "Starting FIX Log Comparison Application"
    
    # Check if already running
    check_running
    
    # Validate required files
    if [ ! -f "$JAR_FILE" ]; then
        log_message "ERROR: JAR file not found: $JAR_FILE"
        exit 1
    fi
    
    if [ ! -f "$CONFIG_FILE" ]; then
        log_message "WARNING: Config file not found: $CONFIG_FILE"
        log_message "Using default configuration"
    fi
    
    # Parse command line arguments
    local comparison_date=""
    if [ $# -gt 0 ]; then
        comparison_date="$1"
        log_message "Using specified comparison date: $comparison_date"
    else
        # Default to yesterday's date
        comparison_date=$(date -d "yesterday" '+%Y%m%d')
        log_message "Using default comparison date (yesterday): $comparison_date"
    fi
    
    # Set Java options
    local java_opts="-Xmx2g -Xms512m"
    java_opts="$java_opts -Dspring.config.location=file:$CONFIG_FILE"
    java_opts="$java_opts -Dlogging.file.name=$LOG_DIR/application.log"
    java_opts="$java_opts -Djava.awt.headless=true"
    
    # Run the application
    log_message "Executing: java $java_opts -jar $JAR_FILE $comparison_date"
    
    # Start application and capture PID
    java $java_opts -jar "$JAR_FILE" "$comparison_date" &
    local app_pid=$!
    echo "$app_pid" > "$PID_FILE"
    
    log_message "Application started with PID: $app_pid"
    
    # Wait for application to complete
    wait $app_pid
    local exit_code=$?
    
    # Remove PID file
    rm -f "$PID_FILE"
    
    # Log completion
    if [ $exit_code -eq 0 ]; then
        log_message "Application completed successfully"
    else
        log_message "Application failed with exit code: $exit_code"
    fi
    
    log_message "FIX Log Comparison Application finished"
    exit $exit_code
}

# Health check function
health_check() {
    log_message "Performing health check..."
    
    # Check Java availability
    if ! command -v java &> /dev/null; then
        log_message "ERROR: Java is not installed or not in PATH"
        return 1
    fi
    
    # Check Java version
    local java_version=$(java -version 2>&1 | head -n 1)
    log_message "Java version: $java_version"
    
    # Check file permissions
    if [ ! -r "$JAR_FILE" ]; then
        log_message "ERROR: Cannot read JAR file: $JAR_FILE"
        return 1
    fi
    
    # Check log directory permissions
    if [ ! -w "$LOG_DIR" ]; then
        log_message "ERROR: Cannot write to log directory: $LOG_DIR"
        return 1
    fi
    
    log_message "Health check passed"
    return 0
}

# Help function
show_help() {
    cat << EOF
FIX Log Comparison Runner Script

Usage:
    $0 [date]                 Run comparison for specified date
    $0 health                 Perform health check
    $0 help                   Show this help message

Examples:
    $0                        Run with yesterday's date
    $0 20231201              Run for December 1, 2023 (yyyyMMdd format)
    $0 2023-12-01            Run for December 1, 2023 (yyyy-MM-dd format)
    $0 health                 Check system health

Environment Variables:
    APP_HOME                  Application home directory (default: /opt/fix-comparison)
    JAVA_OPTS                 Additional Java options

Files:
    $JAR_FILE                 Application JAR file
    $CONFIG_FILE              Application configuration
    $LOG_DIR                  Log directory
    $PID_FILE                 Process ID file

EOF
}

# Main script logic
case "${1:-}" in
    "health")
        health_check
        ;;
    "help"|"-h"|"--help")
        show_help
        ;;
    *)
        main "$@"
        ;;
esac