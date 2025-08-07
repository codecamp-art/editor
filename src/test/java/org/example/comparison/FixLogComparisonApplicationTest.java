package org.example.comparison;

import org.example.comparison.config.ComparisonConfig;
import org.example.comparison.service.*;
import org.example.comparison.service.ComparisonService.ComparisonSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FixLogComparisonApplication
 */
@ExtendWith(MockitoExtension.class)
class FixLogComparisonApplicationTest {

    @Mock
    private ComparisonService comparisonService;

    @Mock
    private ExcelReportService excelReportService;

    @Mock
    private EmailNotificationService emailNotificationService;

    @Mock
    private SftpService sftpService;

    @Mock
    private ComparisonConfig comparisonConfig;

    @Mock
    private ComparisonConfig.DatabaseConfig primaryDbConfig;

    @Mock
    private ComparisonConfig.DatabaseConfig secondaryDbConfig;

    @Mock
    private ComparisonConfig.SftpConfig sftpConfig;

    @Mock
    private ComparisonConfig.EmailConfig emailConfig;

    @Mock
    private ComparisonConfig.ReportConfig reportConfig;

    @TempDir
    File tempDir;

    private FixLogComparisonApplication application;

    @BeforeEach
    void setUp() {
        application = new FixLogComparisonApplication();

        // Setup mock configuration
        when(comparisonConfig.getPrimary()).thenReturn(primaryDbConfig);
        when(comparisonConfig.getSecondary()).thenReturn(secondaryDbConfig);
        when(comparisonConfig.getSftp()).thenReturn(sftpConfig);
        when(comparisonConfig.getEmail()).thenReturn(emailConfig);
        when(comparisonConfig.getReport()).thenReturn(reportConfig);

        // Setup database configs
        when(primaryDbConfig.getUrl()).thenReturn("jdbc:oracle:thin:@primary-db:1521:XE");
        when(primaryDbConfig.getUsername()).thenReturn("primary_user");
        when(primaryDbConfig.getDriverClassName()).thenReturn("oracle.jdbc.OracleDriver");

        when(secondaryDbConfig.getUrl()).thenReturn("jdbc:oracle:thin:@secondary-db:1521:XE");
        when(secondaryDbConfig.getUsername()).thenReturn("secondary_user");
        when(secondaryDbConfig.getDriverClassName()).thenReturn("oracle.jdbc.OracleDriver");

        // Setup SFTP config
        when(sftpConfig.getHost()).thenReturn("sftp.company.com");
        when(sftpConfig.getPort()).thenReturn(22);
        when(sftpConfig.getUsername()).thenReturn("sftp_user");
        when(sftpConfig.getRemoteDirectory()).thenReturn("/fix/logs");

        // Setup email config
        when(emailConfig.getFrom()).thenReturn("noreply@company.com");
        when(emailConfig.getTo()).thenReturn(Arrays.asList("test@company.com"));
        when(emailConfig.getSubject()).thenReturn("FIX Log Comparison Report");

        // Setup report config
        when(reportConfig.getOutputDirectory()).thenReturn(tempDir.getAbsolutePath());
        when(reportConfig.getExcelFileName()).thenReturn("fix-comparison-report.xlsx");
        when(reportConfig.getKeepReportsForDays()).thenReturn(30);
    }

    @Test
    void testMainMethod_WithHelpArgument() {
        // Capture System.out to verify help message
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outputStream));

        // Mock System.exit to prevent actual exit
        try (MockedStatic<System> systemMock = mockStatic(System.class)) {
            systemMock.when(() -> System.setProperty(anyString(), anyString())).thenCallRealMethod();
            systemMock.when(() -> System.exit(0)).thenThrow(new RuntimeException("System.exit(0) called"));

            // When & Then
            assertThrows(RuntimeException.class, () ->
                    FixLogComparisonApplication.main(new String[]{"--help"}));

            String output = outputStream.toString();
            assertTrue(output.contains("FIX Log Comparison Application"));
            assertTrue(output.contains("Usage:"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void testMainMethod_WithHelpShortArgument() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outputStream));

        try (MockedStatic<System> systemMock = mockStatic(System.class)) {
            systemMock.when(() -> System.setProperty(anyString(), anyString())).thenCallRealMethod();
            systemMock.when(() -> System.exit(0)).thenThrow(new RuntimeException("System.exit(0) called"));

            // When & Then
            assertThrows(RuntimeException.class, () ->
                    FixLogComparisonApplication.main(new String[]{"-h"}));

            String output = outputStream.toString();
            assertTrue(output.contains("FIX Log Comparison Application"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void testCommandLineRunner_SuccessfulExecution() throws Exception {
        // Given
        ComparisonSummary summary = createSuccessfulSummary();
        File excelReport = createTempExcelFile();

        when(sftpService.testConnection()).thenReturn(true);
        when(comparisonService.performComparison(any(LocalDate.class))).thenReturn(summary);
        when(excelReportService.generateReport(summary)).thenReturn(excelReport);

        // When
        CommandLineRunner runner = application.commandLineRunner(
                comparisonService, excelReportService, emailNotificationService, sftpService, comparisonConfig);
        runner.run(new String[]{});

        // Then
        verify(sftpService).testConnection();
        verify(comparisonService).performComparison(any(LocalDate.class));
        verify(emailNotificationService).sendComparisonNotification(summary, null);
        verify(sftpService).shutdown();
    }

    @Test
    void testCommandLineRunner_WithDiscrepancies() throws Exception {
        // Given
        ComparisonSummary summary = createSummaryWithDiscrepancies();
        File excelReport = createTempExcelFile();

        when(sftpService.testConnection()).thenReturn(true);
        when(comparisonService.performComparison(any(LocalDate.class))).thenReturn(summary);
        when(excelReportService.generateReport(summary)).thenReturn(excelReport);

        // When
        CommandLineRunner runner = application.commandLineRunner(
                comparisonService, excelReportService, emailNotificationService, sftpService, comparisonConfig);
        runner.run(new String[]{});

        // Then
        verify(comparisonService).performComparison(any(LocalDate.class));
        verify(excelReportService).generateReport(summary);
        verify(emailNotificationService).sendComparisonNotification(summary, excelReport);
        verify(sftpService).shutdown();
    }

    @Test
    void testCommandLineRunner_WithSpecificDate() throws Exception {
        // Given
        ComparisonSummary summary = createSuccessfulSummary();
        when(sftpService.testConnection()).thenReturn(true);
        when(comparisonService.performComparison(any(LocalDate.class))).thenReturn(summary);

        // When
        CommandLineRunner runner = application.commandLineRunner(
                comparisonService, excelReportService, emailNotificationService, sftpService, comparisonConfig);
        runner.run(new String[]{"20231201"});

        // Then
        verify(comparisonService).performComparison(LocalDate.of(2023, 12, 1));
        verify(emailNotificationService).sendComparisonNotification(summary, null);
    }

    @Test
    void testCommandLineRunner_WithDateFormatYyyyMmDd() throws Exception {
        // Given
        ComparisonSummary summary = createSuccessfulSummary();
        when(sftpService.testConnection()).thenReturn(true);
        when(comparisonService.performComparison(any(LocalDate.class))).thenReturn(summary);

        // When
        CommandLineRunner runner = application.commandLineRunner(
                comparisonService, excelReportService, emailNotificationService, sftpService, comparisonConfig);
        runner.run(new String[]{"2023-12-01"});

        // Then
        verify(comparisonService).performComparison(LocalDate.of(2023, 12, 1));
    }

    @Test
    void testCommandLineRunner_InvalidDateFormat() throws Exception {
        // Given
        ComparisonSummary summary = createSuccessfulSummary();
        when(sftpService.testConnection()).thenReturn(true);
        when(comparisonService.performComparison(any(LocalDate.class))).thenReturn(summary);

        // When
        CommandLineRunner runner = application.commandLineRunner(
                comparisonService, excelReportService, emailNotificationService, sftpService, comparisonConfig);
        runner.run(new String[]{"invalid-date"});

        // Then
        // Should use default date (yesterday) when invalid date is provided
        verify(comparisonService).performComparison(any(LocalDate.class));
    }

    @Test
    void testCommandLineRunner_ConnectionTestFailed() throws Exception {
        // Given
        when(sftpService.testConnection()).thenReturn(false);

        try (MockedStatic<System> systemMock = mockStatic(System.class)) {
            systemMock.when(() -> System.exit(1)).thenThrow(new RuntimeException("System.exit(1) called"));

            // When & Then
            CommandLineRunner runner = application.commandLineRunner(
                    comparisonService, excelReportService, emailNotificationService, sftpService, comparisonConfig);

            assertThrows(RuntimeException.class, () -> runner.run(new String[]{}));
            verify(sftpService).testConnection();
            verify(comparisonService, never()).performComparison(any(LocalDate.class));
        }
    }

    @Test
    void testCommandLineRunner_ConnectionTestException() throws Exception {
        // Given
        when(sftpService.testConnection()).thenThrow(new RuntimeException("Connection failed"));

        try (MockedStatic<System> systemMock = mockStatic(System.class)) {
            systemMock.when(() -> System.exit(1)).thenThrow(new RuntimeException("System.exit(1) called"));

            // When & Then
            CommandLineRunner runner = application.commandLineRunner(
                    comparisonService, excelReportService, emailNotificationService, sftpService, comparisonConfig);

            assertThrows(RuntimeException.class, () -> runner.run(new String[]{}));
            verify(sftpService).testConnection();
        }
    }

    @Test
    void testCommandLineRunner_ComparisonException() throws Exception {
        // Given
        when(sftpService.testConnection()).thenReturn(true);
        when(comparisonService.performComparison(any(LocalDate.class)))
                .thenThrow(new RuntimeException("Comparison failed"));

        try (MockedStatic<System> systemMock = mockStatic(System.class)) {
            systemMock.when(() -> System.exit(1)).thenThrow(new RuntimeException("System.exit(1) called"));

            // When & Then
            CommandLineRunner runner = application.commandLineRunner(
                    comparisonService, excelReportService, emailNotificationService, sftpService, comparisonConfig);

            assertThrows(RuntimeException.class, () -> runner.run(new String[]{}));
            verify(sftpService).shutdown(); // Should still cleanup
        }
    }

    @Test
    void testParseComparisonDate_NoArguments() throws Exception {
        // When
        LocalDate result = invokePrivateMethod_parseComparisonDate(new String[]{});

        // Then
        assertEquals(LocalDate.now().minusDays(1), result);
    }

    @Test
    void testParseComparisonDate_ValidYyyyMmDdFormat() throws Exception {
        // When
        LocalDate result = invokePrivateMethod_parseComparisonDate(new String[]{"20231201"});

        // Then
        assertEquals(LocalDate.of(2023, 12, 1), result);
    }

    @Test
    void testParseComparisonDate_ValidYyyyMmDdDashFormat() throws Exception {
        // When
        LocalDate result = invokePrivateMethod_parseComparisonDate(new String[]{"2023-12-01"});

        // Then
        assertEquals(LocalDate.of(2023, 12, 1), result);
    }

    @Test
    void testParseComparisonDate_InvalidFormat() throws Exception {
        // When
        LocalDate result = invokePrivateMethod_parseComparisonDate(new String[]{"invalid"});

        // Then
        assertEquals(LocalDate.now().minusDays(1), result);
    }

    @Test
    void testMaskSensitiveUrl() throws Exception {
        // When
        String result1 = invokePrivateMethod_maskSensitiveUrl("jdbc:oracle:thin:user:password@localhost:1521:XE");
        String result2 = invokePrivateMethod_maskSensitiveUrl("jdbc:oracle:thin:@localhost:1521:XE");
        String result3 = invokePrivateMethod_maskSensitiveUrl(null);

        // Then
        assertEquals("jdbc:oracle:thin:user:****@localhost:1521:XE", result1);
        assertEquals("jdbc:oracle:thin:@localhost:1521:XE", result2);
        assertEquals("null", result3);
    }

    @Test
    void testTestConnections_Success() throws Exception {
        // Given
        when(sftpService.testConnection()).thenReturn(true);

        // When
        boolean result = invokePrivateMethod_testConnections(sftpService);

        // Then
        assertTrue(result);
        verify(sftpService).testConnection();
    }

    @Test
    void testTestConnections_Failure() throws Exception {
        // Given
        when(sftpService.testConnection()).thenReturn(false);

        // When
        boolean result = invokePrivateMethod_testConnections(sftpService);

        // Then
        assertFalse(result);
        verify(sftpService).testConnection();
    }

    @Test
    void testTestConnections_Exception() throws Exception {
        // Given
        when(sftpService.testConnection()).thenThrow(new RuntimeException("Connection error"));

        // When
        boolean result = invokePrivateMethod_testConnections(sftpService);

        // Then
        assertFalse(result);
        verify(sftpService).testConnection();
    }

    // Helper methods
    private ComparisonSummary createSuccessfulSummary() {
        ComparisonSummary summary = new ComparisonSummary();
        summary.setComparisonDate(LocalDate.now().minusDays(1));
        summary.setTotalDbRecords(100);
        summary.setTotalFixLogFiles(5);
        summary.setTotalFixMessages(50);
        summary.setDiscrepancies(Collections.emptyList());
        summary.setTotalDiscrepancies(0);
        return summary;
    }

    private ComparisonSummary createSummaryWithDiscrepancies() {
        ComparisonSummary summary = new ComparisonSummary();
        summary.setComparisonDate(LocalDate.now().minusDays(1));
        summary.setTotalDbRecords(100);
        summary.setTotalFixLogFiles(5);
        summary.setTotalFixMessages(50);
        summary.setTotalDiscrepancies(2);
        return summary;
    }

    private File createTempExcelFile() {
        try {
            File excelFile = new File(tempDir, "test-report.xlsx");
            excelFile.createNewFile();
            return excelFile;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create temp excel file", e);
        }
    }

    // Helper methods to access private methods using reflection
    private LocalDate invokePrivateMethod_parseComparisonDate(String[] args) throws Exception {
        java.lang.reflect.Method method = FixLogComparisonApplication.class
                .getDeclaredMethod("parseComparisonDate", String[].class);
        method.setAccessible(true);
        return (LocalDate) method.invoke(application, (Object) args);
    }

    private String invokePrivateMethod_maskSensitiveUrl(String url) throws Exception {
        java.lang.reflect.Method method = FixLogComparisonApplication.class
                .getDeclaredMethod("maskSensitiveUrl", String.class);
        method.setAccessible(true);
        return (String) method.invoke(application, url);
    }

    private boolean invokePrivateMethod_testConnections(SftpService sftpService) throws Exception {
        java.lang.reflect.Method method = FixLogComparisonApplication.class
                .getDeclaredMethod("testConnections", SftpService.class);
        method.setAccessible(true);
        return (boolean) method.invoke(application, sftpService);
    }
}