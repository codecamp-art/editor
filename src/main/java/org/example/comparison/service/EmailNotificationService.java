package org.example.comparison.service;

import org.example.comparison.config.ComparisonConfig;
import org.example.comparison.domain.ComparisonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for sending email notifications about comparison results
 */
@Service
public class EmailNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(EmailNotificationService.class);
    
    private final JavaMailSender mailSender;
    private final ComparisonConfig.EmailConfig emailConfig;
    private final Environment environment;

    public EmailNotificationService(JavaMailSender mailSender, ComparisonConfig comparisonConfig, Environment environment) {
        this.mailSender = mailSender;
        this.emailConfig = comparisonConfig.getEmail();
        this.environment = environment;
    }

    /**
     * Sends email notification based on comparison results
     */
    public void sendComparisonNotification(ComparisonService.ComparisonSummary summary, File excelReport) {
        try {
            if (summary.hasDiscrepancies()) {
                sendDiscrepancyNotification(summary, excelReport);
            } else {
                sendNoDiscrepancyNotification(summary);
            }
            logger.info("Email notification sent successfully");
        } catch (Exception e) {
            logger.error("Failed to send email notification", e);
            throw new RuntimeException("Email notification failed", e);
        }
    }

    /**
     * Sends notification when discrepancies are found
     */
    private void sendDiscrepancyNotification(ComparisonService.ComparisonSummary summary, File excelReport) 
            throws MessagingException {
        
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        // Email headers
        helper.setFrom(emailConfig.getFrom());
        helper.setTo(emailConfig.getTo().toArray(new String[0]));
        if (emailConfig.getCc() != null && !emailConfig.getCc().isEmpty()) {
            helper.setCc(emailConfig.getCc().toArray(new String[0]));
        }
        helper.setSubject(getEnvironmentSubject(emailConfig.getSubject()) + " - Discrepancies Found");

        // Email body
        String htmlContent = buildDiscrepancyEmailContent(summary);
        helper.setText(htmlContent, true);

        // Attach Excel report
        if (excelReport != null && excelReport.exists()) {
            FileSystemResource file = new FileSystemResource(excelReport);
            helper.addAttachment(excelReport.getName(), file);
        }

        mailSender.send(message);
    }

    /**
     * Sends notification when no discrepancies are found
     */
    private void sendNoDiscrepancyNotification(ComparisonService.ComparisonSummary summary) 
            throws MessagingException {
        
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        // Email headers
        helper.setFrom(emailConfig.getFrom());
        helper.setTo(emailConfig.getTo().toArray(new String[0]));
        if (emailConfig.getCc() != null && !emailConfig.getCc().isEmpty()) {
            helper.setCc(emailConfig.getCc().toArray(new String[0]));
        }
        helper.setSubject(getEnvironmentSubject(emailConfig.getSubject()) + " - No Discrepancies");

        // Email body
        String htmlContent = buildNoDiscrepancyEmailContent(summary);
        helper.setText(htmlContent, true);

        mailSender.send(message);
    }

    /**
     * Builds HTML email content for discrepancy notification
     */
    private String buildDiscrepancyEmailContent(ComparisonService.ComparisonSummary summary) {
        StringBuilder html = new StringBuilder();
        
        html.append("<!DOCTYPE html>")
            .append("<html><head><meta charset='UTF-8'>")
            .append("<style>")
            .append("body { font-family: Arial, sans-serif; margin: 20px; }")
            .append("table { border-collapse: collapse; width: 100%; margin: 20px 0; }")
            .append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }")
            .append("th { background-color: #f2f2f2; font-weight: bold; }")
            .append(".alert { color: #d9534f; font-weight: bold; }")
            .append(".summary { background-color: #f9f9f9; padding: 15px; border-radius: 5px; }")
            .append("</style></head><body>");

        // Header
        html.append("<h2 class='alert'>⚠️ Daily FIX EOD Reconciliation Report - Discrepancies Found</h2>");

        // Summary section
        html.append("<div class='summary'>")
            .append("<h3>Summary</h3>")
            .append("<p><strong>Report Type:</strong> Daily FIX End-of-Day Reconciliation Report</p>")
            .append("<p><strong>Scope:</strong> This report compares only <strong>fulfilled and partial fulfilled orders</strong> between database records and FIX log messages.</p>")
            .append("<p><strong>Comparison Date:</strong> ").append(summary.getComparisonDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))).append("</p>")
            .append("<p><strong>Total Database Records:</strong> ").append(summary.getTotalDbRecords()).append("</p>")
            .append("<p><strong>Total FIX Log Files:</strong> ").append(summary.getTotalFixLogFiles()).append("</p>")
            .append("<p><strong>Total FIX Messages:</strong> ").append(summary.getTotalFixMessages()).append("</p>")
            .append("<p class='alert'><strong>Total Discrepancies:</strong> ").append(summary.getTotalDiscrepancies()).append("</p>")
            .append("<p><strong>Report Generated:</strong> ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("</p>")
            .append("</div>");

        // Discrepancy breakdown
        if (!summary.getDiscrepancies().isEmpty()) {
            html.append("<h3>Discrepancy Breakdown</h3>");
            
            // By type
            Map<String, Long> byType = summary.getDiscrepancies().stream()
                    .collect(Collectors.groupingBy(ComparisonResult::getDiscrepancyType, Collectors.counting()));
            
            html.append("<h4>By Type:</h4>")
                .append("<table>")
                .append("<tr><th>Discrepancy Type</th><th>Count</th></tr>");
            
            for (Map.Entry<String, Long> entry : byType.entrySet()) {
                html.append("<tr><td>").append(entry.getKey()).append("</td><td>").append(entry.getValue()).append("</td></tr>");
            }
            html.append("</table>");
            
            // By field
            Map<String, Long> byField = summary.getDiscrepancies().stream()
                    .collect(Collectors.groupingBy(ComparisonResult::getField, Collectors.counting()));
            
            html.append("<h4>By Field:</h4>")
                .append("<table>")
                .append("<tr><th>Field Name</th><th>Count</th></tr>");
            
            for (Map.Entry<String, Long> entry : byField.entrySet()) {
                html.append("<tr><td>").append(entry.getKey()).append("</td><td>").append(entry.getValue()).append("</td></tr>");
            }
            html.append("</table>");

            // Sample discrepancies (first 10)
            html.append("<h4>Sample Discrepancies (First 10):</h4>")
                .append("<table>")
                .append("<tr><th>DONE_NO</th><th>EXEC_ID</th><th>Field</th><th>DB Value</th><th>FIX Value</th><th>Type</th></tr>");
            
            List<ComparisonResult> sampleDiscrepancies = summary.getDiscrepancies().stream()
                    .limit(10)
                    .collect(Collectors.toList());
            
            for (ComparisonResult discrepancy : sampleDiscrepancies) {
                html.append("<tr>")
                    .append("<td>").append(nvl(discrepancy.getDoneNo())).append("</td>")
                    .append("<td>").append(nvl(discrepancy.getExecId())).append("</td>")
                    .append("<td>").append(nvl(discrepancy.getField())).append("</td>")
                    .append("<td>").append(nvl(discrepancy.getDatabaseValue())).append("</td>")
                    .append("<td>").append(nvl(discrepancy.getFixValue())).append("</td>")
                    .append("<td>").append(nvl(discrepancy.getDiscrepancyType())).append("</td>")
                    .append("</tr>");
            }
            html.append("</table>");
        }

        // Footer
        html.append("<p><strong>Action Required:</strong> Please review the attached Excel report for detailed analysis.</p>")
            .append("<p><em>This is an automated notification from the Daily FIX EOD Reconciliation System.</em></p>")
            .append("</body></html>");

        return html.toString();
    }

    /**
     * Builds HTML email content for no discrepancy notification
     */
    private String buildNoDiscrepancyEmailContent(ComparisonService.ComparisonSummary summary) {
        StringBuilder html = new StringBuilder();
        
        html.append("<!DOCTYPE html>")
            .append("<html><head><meta charset='UTF-8'>")
            .append("<style>")
            .append("body { font-family: Arial, sans-serif; margin: 20px; }")
            .append(".success { color: #5cb85c; font-weight: bold; }")
            .append(".summary { background-color: #f9f9f9; padding: 15px; border-radius: 5px; }")
            .append("</style></head><body>");

        // Header
        html.append("<h2 class='success'>✅ Daily FIX EOD Reconciliation Report - No Discrepancies Found</h2>");

        // Summary section
        html.append("<div class='summary'>")
            .append("<h3>Summary</h3>")
            .append("<p><strong>Report Type:</strong> Daily FIX End-of-Day Reconciliation Report</p>")
            .append("<p><strong>Scope:</strong> This report compares only <strong>fulfilled and partial fulfilled orders</strong> between database records and FIX log messages.</p>")
            .append("<p><strong>Comparison Date:</strong> ").append(summary.getComparisonDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))).append("</p>")
            .append("<p><strong>Total Database Records:</strong> ").append(summary.getTotalDbRecords()).append("</p>")
            .append("<p><strong>Total FIX Log Files:</strong> ").append(summary.getTotalFixLogFiles()).append("</p>")
            .append("<p><strong>Total FIX Messages:</strong> ").append(summary.getTotalFixMessages()).append("</p>")
            .append("<p class='success'><strong>Total Discrepancies:</strong> 0</p>")
            .append("<p><strong>Report Generated:</strong> ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("</p>")
            .append("</div>");

        // Success message
        html.append("<p class='success'><strong>All fulfilled and partial fulfilled orders match perfectly between database records and FIX log messages!</strong></p>")
            .append("<p>No action is required at this time.</p>")
            .append("<p><em>This is an automated notification from the Daily FIX EOD Reconciliation System.</em></p>")
            .append("</body></html>");

        return html.toString();
    }

    /**
     * Gets the environment-specific subject with appropriate prefix
     */
    private String getEnvironmentSubject(String baseSubject) {
        String[] activeProfiles = environment.getActiveProfiles();
        
        // Check for specific environments and add appropriate prefix
        if (Arrays.asList(activeProfiles).contains("dev")) {
            return "[DEV] " + baseSubject;
        } else if (Arrays.asList(activeProfiles).contains("test")) {
            return "[TEST] " + baseSubject;
        } else if (Arrays.asList(activeProfiles).contains("docker")) {
            return "[DOCKER] " + baseSubject;
        } else if (Arrays.asList(activeProfiles).contains("prod")) {
            return "[PROD] " + baseSubject;
        }
        
        // For production or default environment, return as is
        return baseSubject;
    }

    /**
     * Null-safe string conversion
     */
    private String nvl(String value) {
        return value != null ? value : "NULL";
    }
}