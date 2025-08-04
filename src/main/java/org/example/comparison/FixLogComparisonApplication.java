package org.example.comparison;

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
        SpringApplication.run(FixLogComparisonApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(ComparisonService comparisonService,
                                             ExcelReportService excelReportService,
                                             EmailNotificationService emailNotificationService,
                                             SftpService sftpService) {
        return args -> {
            logger.info("Starting FIX Log Comparison Application");
            
            try {
                // Parse command line arguments
                LocalDate comparisonDate = parseComparisonDate(args);
                
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