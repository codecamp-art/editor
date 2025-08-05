package org.example.comparison;

import org.example.comparison.config.ComparisonConfig;
import org.example.comparison.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Main Spring Boot application for FIX log comparison
 */
@SpringBootApplication
@EnableConfigurationProperties
public class FixLogComparisonApplication {

    private static final Logger logger = LoggerFactory.getLogger(FixLogComparisonApplication.class);

    public static void main(String[] args) {
        // Check for help argument before starting Spring
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            printUsageAndExit();
        }
        
        SpringApplication app = new SpringApplication(FixLogComparisonApplication.class);
        
        // Enable property override logging
        System.setProperty("logging.level.org.springframework.boot.context.config", "INFO");
        
        app.run(args);
    }

    /**
     * Prints usage information and exits
     */
    private static void printUsageAndExit() {
        System.out.println("\nFIX Log Comparison Application");
        System.out.println("Usage: java -jar fix-log-comparison.jar [DATE] [OPTIONS]");
        System.out.println("\nArguments:");
        System.out.println("  DATE                     Comparison date in yyyyMMdd or yyyy-MM-dd format");
        System.out.println("                           (optional, defaults to yesterday)");
        System.out.println("\nConfiguration Override Options:");
        System.out.println("  --comparison.primary.url=URL               Override primary database URL");
        System.out.println("  --comparison.secondary.url=URL             Override secondary database URL");
        System.out.println("  --comparison.sftp.host=HOSTNAME             Override SFTP server hostname");
        System.out.println("  --spring.mail.host=HOSTNAME                 Override mail server hostname");
        System.out.println("  --comparison.primary.username=USER          Override primary DB username");
        System.out.println("  --comparison.primary.password=PASS          Override primary DB password");
        System.out.println("  --comparison.secondary.username=USER        Override secondary DB username");
        System.out.println("  --comparison.secondary.password=PASS        Override secondary DB password");
        System.out.println("  --comparison.sftp.username=USER             Override SFTP username");
        System.out.println("  --comparison.sftp.password=PASS             Override SFTP password");
        System.out.println("  -h, --help                                  Show this help message");
        System.out.println("\nEnvironment Variables (alternative to command line):");
        System.out.println("  PRIMARY_DB_HOST, PRIMARY_DB_PORT, PRIMARY_DB_SID");
        System.out.println("  SECONDARY_DB_HOST, SECONDARY_DB_PORT, SECONDARY_DB_SID");
        System.out.println("  SFTP_HOST, SFTP_PORT, SFTP_USER, SFTP_PASSWORD");
        System.out.println("  MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD");
        System.out.println("\nExamples:");
        System.out.println("  # Run with default configuration");
        System.out.println("  java -jar fix-log-comparison.jar");
        System.out.println("");
        System.out.println("  # Run with specific date");
        System.out.println("  java -jar fix-log-comparison.jar 20231201");
        System.out.println("");
        System.out.println("  # Override primary database URL");
        System.out.println("  java -jar fix-log-comparison.jar \\");
        System.out.println("    --comparison.primary.url=jdbc:oracle:thin:@backup-db:1521:XE");
        System.out.println("");
        System.out.println("  # Override SFTP server hostname");
        System.out.println("  java -jar fix-log-comparison.jar 20231201 \\");
        System.out.println("    --comparison.sftp.host=backup-sftp.company.com");
        System.out.println("");
        System.out.println("  # Override multiple settings");
        System.out.println("  java -jar fix-log-comparison.jar \\");
        System.out.println("    --comparison.primary.url=jdbc:oracle:thin:@backup-db:1521:XE \\");
        System.out.println("    --comparison.sftp.host=backup-sftp.company.com \\");
        System.out.println("    --spring.mail.host=backup-mail.company.com");
        System.out.println("");
        System.exit(0);
    }

    @Bean
    public CommandLineRunner commandLineRunner(ComparisonService comparisonService,
                                             ExcelReportService excelReportService,
                                             EmailNotificationService emailNotificationService,
                                             SftpService sftpService,
                                             ComparisonConfig comparisonConfig) {
        return args -> {
            logger.info("Starting FIX Log Comparison Application");
            
            try {
                // Parse command line arguments
                LocalDate comparisonDate = parseComparisonDate(args);
                
                // Log current configuration (masking sensitive information)
                logCurrentConfiguration(comparisonConfig);
                
                // Test connections before starting
                if (!testConnections(sftpService)) {
                    logger.error("Connection tests failed. Exiting application.");
                    System.exit(1);
                }
                
                // Perform comparison
                ComparisonService.ComparisonSummary summary = comparisonService.performComparison(comparisonDate);
                
                // Generate report if there are discrepancies
                File excelReport = null;
                if (summary.hasDiscrepancies()) {
                    logger.info("Generating Excel report for {} discrepancies", summary.getTotalDiscrepancies());
                    excelReport = excelReportService.generateReport(summary);
                }
                
                // Send email notification
                logger.info("Sending email notification");
                emailNotificationService.sendComparisonNotification(summary, excelReport);
                
                // Log final results
                logFinalResults(summary);
                
                logger.info("FIX Log Comparison completed successfully");
                
            } catch (Exception e) {
                logger.error("Error during FIX log comparison", e);
                System.exit(1);
            } finally {
                // Cleanup resources
                sftpService.shutdown();
            }
        };
    }

    /**
     * Parses comparison date from command line arguments
     */
    private LocalDate parseComparisonDate(String[] args) {
        LocalDate comparisonDate = LocalDate.now().minusDays(1); // Default to yesterday
        
        if (args.length > 0) {
            try {
                // Support different date formats
                String dateArg = args[0];
                if (dateArg.length() == 8) {
                    // yyyyMMdd format
                    comparisonDate = LocalDate.parse(dateArg, DateTimeFormatter.ofPattern("yyyyMMdd"));
                } else if (dateArg.length() == 10) {
                    // yyyy-MM-dd format
                    comparisonDate = LocalDate.parse(dateArg, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                } else {
                    logger.warn("Invalid date format: {}. Using default date: {}", dateArg, comparisonDate);
                }
            } catch (DateTimeParseException e) {
                logger.warn("Failed to parse date: {}. Using default date: {}", args[0], comparisonDate);
            }
        }
        
        logger.info("Comparison date: {}", comparisonDate);
        return comparisonDate;
    }

    /**
     * Logs current configuration with sensitive information masked
     */
    private void logCurrentConfiguration(ComparisonConfig config) {
        logger.info("=== CURRENT CONFIGURATION ===");
        
        // Database configuration
        logger.info("Primary Database:");
        logger.info("  URL: {}", maskSensitiveUrl(config.getPrimary().getUrl()));
        logger.info("  Username: {}", config.getPrimary().getUsername());
        logger.info("  Driver: {}", config.getPrimary().getDriverClassName());
        
        logger.info("Secondary Database:");
        logger.info("  URL: {}", maskSensitiveUrl(config.getSecondary().getUrl()));
        logger.info("  Username: {}", config.getSecondary().getUsername());
        logger.info("  Driver: {}", config.getSecondary().getDriverClassName());
        
        // SFTP configuration
        logger.info("SFTP Configuration:");
        logger.info("  Host: {}", config.getSftp().getHost());
        logger.info("  Port: {}", config.getSftp().getPort());
        logger.info("  Username: {}", config.getSftp().getUsername());
        logger.info("  Remote Directory: {}", config.getSftp().getRemoteDirectory());
        
        // Email configuration
        logger.info("Email Configuration:");
        logger.info("  From: {}", config.getEmail().getFrom());
        logger.info("  To: {}", config.getEmail().getTo());
        logger.info("  Subject: {}", config.getEmail().getSubject());
        
        // Report configuration
        logger.info("Report Configuration:");
        logger.info("  Output Directory: {}", config.getReport().getOutputDirectory());
        logger.info("  Excel File Name: {}", config.getReport().getExcelFileName());
        logger.info("  Keep Reports For Days: {}", config.getReport().getKeepReportsForDays());
        
        logger.info("=============================");
    }

    /**
     * Masks sensitive information in URLs for logging
     */
    private String maskSensitiveUrl(String url) {
        if (url == null) return "null";
        // Hide password if present in URL
        return url.replaceAll("://([^:]+):([^@]+)@", "://$1:****@");
    }

    /**
     * Tests all external connections
     */
    private boolean testConnections(SftpService sftpService) {
        logger.info("Testing external connections...");
        
        boolean allTestsPassed = true;
        
        // Test SFTP connection
        try {
            if (sftpService.testConnection()) {
                logger.info("✓ SFTP connection test passed");
            } else {
                logger.error("✗ SFTP connection test failed");
                allTestsPassed = false;
            }
        } catch (Exception e) {
            logger.error("✗ SFTP connection test failed with exception", e);
            allTestsPassed = false;
        }
        
        // TODO: Add database connection tests here if needed
        // The repositories will fail during actual operations if DB connections are invalid
        
        return allTestsPassed;
    }

    /**
     * Logs final comparison results
     */
    private void logFinalResults(ComparisonService.ComparisonSummary summary) {
        logger.info("=== COMPARISON RESULTS ===");
        logger.info("Comparison Date: {}", summary.getComparisonDate());
        logger.info("Total Database Records: {}", summary.getTotalDbRecords());
        logger.info("Total FIX Log Files: {}", summary.getTotalFixLogFiles());
        logger.info("Total FIX Messages: {}", summary.getTotalFixMessages());
        logger.info("Total Discrepancies: {}", summary.getTotalDiscrepancies());
        
        if (summary.hasError()) {
            logger.error("Error during comparison: {}", summary.getErrorMessage());
        }
        
        if (summary.hasDiscrepancies()) {
            logger.warn("⚠️  DISCREPANCIES FOUND - Manual review required");
        } else {
            logger.info("✅ NO DISCREPANCIES - All records match perfectly");
        }
        
        logger.info("========================");
    }
}