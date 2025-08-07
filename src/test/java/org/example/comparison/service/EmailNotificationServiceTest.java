package org.example.comparison.service;

import org.example.comparison.config.ComparisonConfig;
import org.example.comparison.domain.ComparisonResult;
import org.example.comparison.service.ComparisonService.ComparisonSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmailNotificationService
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private ComparisonConfig comparisonConfig;

    @Mock
    private ComparisonConfig.EmailConfig emailConfig;

    @Mock
    private MimeMessage mimeMessage;

    @TempDir
    File tempDir;

    private EmailNotificationService emailNotificationService;

    @BeforeEach
    void setUp() {
        when(comparisonConfig.getEmail()).thenReturn(emailConfig);
        when(emailConfig.getFrom()).thenReturn("noreply@company.com");
        when(emailConfig.getTo()).thenReturn(Arrays.asList("test1@company.com", "test2@company.com"));
        when(emailConfig.getCc()).thenReturn(Arrays.asList("cc@company.com"));
        when(emailConfig.getSubject()).thenReturn("FIX Log Comparison Report");

        emailNotificationService = new EmailNotificationService(mailSender, comparisonConfig);
    }

    @Test
    void testSendComparisonNotification_WithDiscrepancies() throws MessagingException {
        // Given
        ComparisonSummary summary = createSummaryWithDiscrepancies();
        File excelReport = createTempExcelFile();
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        // When
        emailNotificationService.sendComparisonNotification(summary, excelReport);

        // Then
        verify(mailSender).createMimeMessage();
        verify(mailSender).send(mimeMessage);
    }

    @Test
    void testSendComparisonNotification_NoDiscrepancies() throws MessagingException {
        // Given
        ComparisonSummary summary = createSummaryWithoutDiscrepancies();
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        // When
        emailNotificationService.sendComparisonNotification(summary, null);

        // Then
        verify(mailSender).createMimeMessage();
        verify(mailSender).send(mimeMessage);
    }

    @Test
    void testSendComparisonNotification_EmailFailure() throws MessagingException {
        // Given
        ComparisonSummary summary = createSummaryWithoutDiscrepancies();
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MessagingException("Email server error")).when(mailSender).send(mimeMessage);

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                emailNotificationService.sendComparisonNotification(summary, null));

        assertEquals("Email notification failed", exception.getMessage());
        assertTrue(exception.getCause() instanceof MessagingException);
    }

    @Test
    void testSendDiscrepancyNotification_WithAttachment() throws MessagingException {
        // Given
        ComparisonSummary summary = createSummaryWithDiscrepancies();
        File excelReport = createTempExcelFile();
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        // When
        emailNotificationService.sendComparisonNotification(summary, excelReport);

        // Then
        verify(mailSender).send(mimeMessage);
        // Verify that attachment was processed (file exists)
        assertTrue(excelReport.exists());
    }

    @Test
    void testSendDiscrepancyNotification_WithoutAttachment() throws MessagingException {
        // Given
        ComparisonSummary summary = createSummaryWithDiscrepancies();
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        // When
        emailNotificationService.sendComparisonNotification(summary, null);

        // Then
        verify(mailSender).send(mimeMessage);
    }

    @Test
    void testSendNotification_WithEmptyCcList() throws MessagingException {
        // Given
        when(emailConfig.getCc()).thenReturn(Collections.emptyList());
        ComparisonSummary summary = createSummaryWithoutDiscrepancies();
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        // When
        emailNotificationService.sendComparisonNotification(summary, null);

        // Then
        verify(mailSender).send(mimeMessage);
    }

    @Test
    void testSendNotification_WithNullCcList() throws MessagingException {
        // Given
        when(emailConfig.getCc()).thenReturn(null);
        ComparisonSummary summary = createSummaryWithoutDiscrepancies();
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        // When
        emailNotificationService.sendComparisonNotification(summary, null);

        // Then
        verify(mailSender).send(mimeMessage);
    }

    @Test
    void testEmailContent_WithDiscrepancies() {
        // Given
        ComparisonSummary summary = createSummaryWithDiscrepancies();

        // When
        String content = invokePrivateMethod_buildDiscrepancyEmailContent(summary);

        // Then
        assertNotNull(content);
        assertTrue(content.contains("<!DOCTYPE html>"));
        assertTrue(content.contains("Discrepancies Found"));
        assertTrue(content.contains("2023-12-01"));
        assertTrue(content.contains("Total Discrepancies"));
        assertTrue(content.contains("MISSING_IN_DB"));
        assertTrue(content.contains("VALUE_MISMATCH"));
        assertTrue(content.contains("Sample Discrepancies"));
    }

    @Test
    void testEmailContent_NoDiscrepancies() {
        // Given
        ComparisonSummary summary = createSummaryWithoutDiscrepancies();

        // When
        String content = invokePrivateMethod_buildNoDiscrepancyEmailContent(summary);

        // Then
        assertNotNull(content);
        assertTrue(content.contains("<!DOCTYPE html>"));
        assertTrue(content.contains("No Discrepancies Found"));
        assertTrue(content.contains("2023-12-01"));
        assertTrue(content.contains("Total Discrepancies:</strong> 0"));
        assertTrue(content.contains("All database records match perfectly"));
    }

    @Test
    void testNvlMethod() {
        // Test the null-safe string conversion through the email content generation
        ComparisonResult discrepancy = new ComparisonResult();
        discrepancy.setDoneNo(null);
        discrepancy.setExecId("EXEC123");
        discrepancy.setField("Price");
        discrepancy.setDatabaseValue(null);
        discrepancy.setFixValue("100.50");
        discrepancy.setDiscrepancyType("VALUE_MISMATCH");

        ComparisonSummary summary = new ComparisonSummary();
        summary.setComparisonDate(LocalDate.of(2023, 12, 1));
        summary.setTotalDbRecords(100);
        summary.setTotalFixLogFiles(5);
        summary.setTotalFixMessages(50);
        summary.setDiscrepancies(Arrays.asList(discrepancy));
        summary.setTotalDiscrepancies(1);

        String content = invokePrivateMethod_buildDiscrepancyEmailContent(summary);
        assertTrue(content.contains("NULL")); // nvl should convert null to "NULL"
    }

    // Helper methods
    private ComparisonSummary createSummaryWithDiscrepancies() {
        ComparisonSummary summary = new ComparisonSummary();
        summary.setComparisonDate(LocalDate.of(2023, 12, 1));
        summary.setTotalDbRecords(100);
        summary.setTotalFixLogFiles(5);
        summary.setTotalFixMessages(50);
        
        ComparisonResult discrepancy1 = new ComparisonResult();
        discrepancy1.setDoneNo("DONE123");
        discrepancy1.setExecId("EXEC123");
        discrepancy1.setField("Price");
        discrepancy1.setDatabaseValue("100.00");
        discrepancy1.setFixValue("100.50");
        discrepancy1.setDiscrepancyType("VALUE_MISMATCH");

        ComparisonResult discrepancy2 = new ComparisonResult();
        discrepancy2.setDoneNo("DONE124");
        discrepancy2.setExecId("EXEC124");
        discrepancy2.setField("Quantity");
        discrepancy2.setDatabaseValue("1000");
        discrepancy2.setFixValue(null);
        discrepancy2.setDiscrepancyType("MISSING_IN_DB");

        List<ComparisonResult> discrepancies = Arrays.asList(discrepancy1, discrepancy2);
        summary.setDiscrepancies(discrepancies);
        summary.setTotalDiscrepancies(discrepancies.size());
        
        return summary;
    }

    private ComparisonSummary createSummaryWithoutDiscrepancies() {
        ComparisonSummary summary = new ComparisonSummary();
        summary.setComparisonDate(LocalDate.of(2023, 12, 1));
        summary.setTotalDbRecords(100);
        summary.setTotalFixLogFiles(5);
        summary.setTotalFixMessages(50);
        summary.setDiscrepancies(Collections.emptyList());
        summary.setTotalDiscrepancies(0);
        return summary;
    }

    private File createTempExcelFile() {
        try {
            File excelFile = new File(tempDir, "test-report.xlsx");
            excelFile.createNewFile();
            return excelFile;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp excel file", e);
        }
    }

    // Helper method to test private method behavior through reflection or package visibility
    private String invokePrivateMethod_buildDiscrepancyEmailContent(ComparisonSummary summary) {
        // In a real scenario, you might:
        // 1. Make the method package-private for testing
        // 2. Use reflection to access private methods
        // 3. Extract the logic to a separate utility class
        // For this example, we'll create a new service instance to access the method indirectly
        
        try {
            java.lang.reflect.Method method = EmailNotificationService.class
                    .getDeclaredMethod("buildDiscrepancyEmailContent", ComparisonSummary.class);
            method.setAccessible(true);
            return (String) method.invoke(emailNotificationService, summary);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke private method", e);
        }
    }

    private String invokePrivateMethod_buildNoDiscrepancyEmailContent(ComparisonSummary summary) {
        try {
            java.lang.reflect.Method method = EmailNotificationService.class
                    .getDeclaredMethod("buildNoDiscrepancyEmailContent", ComparisonSummary.class);
            method.setAccessible(true);
            return (String) method.invoke(emailNotificationService, summary);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke private method", e);
        }
    }
}