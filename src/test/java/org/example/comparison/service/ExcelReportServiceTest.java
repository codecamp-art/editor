package org.example.comparison.service;

import org.example.comparison.config.ComparisonConfig;
import org.example.comparison.domain.ComparisonResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExcelReportService
 */
@ExtendWith(MockitoExtension.class)
class ExcelReportServiceTest {

    @Mock
    private ComparisonConfig comparisonConfig;

    @Mock
    private ComparisonConfig.ReportConfig reportConfig;

    @TempDir
    Path tempDir;

    private ExcelReportService excelReportService;

    @BeforeEach
    void setUp() {
        when(comparisonConfig.getReport()).thenReturn(reportConfig);
        when(reportConfig.getOutputDirectory()).thenReturn(tempDir.toString());
        when(reportConfig.getExcelFileName()).thenReturn("test_report_{date}.xlsx");
        when(reportConfig.isDeleteOldReports()).thenReturn(false);
        
        excelReportService = new ExcelReportService(comparisonConfig);
    }

    @Test
    void testGenerateReport_WithDiscrepancies() {
        // Setup test data
        ComparisonService.ComparisonSummary summary = createSummaryWithDiscrepancies();

        // Execute
        File reportFile = excelReportService.generateReport(summary);

        // Verify
        assertNotNull(reportFile);
        assertTrue(reportFile.exists());
        assertTrue(reportFile.getName().endsWith(".xlsx"));
        assertTrue(reportFile.getName().contains("20231201"));
        assertTrue(reportFile.length() > 0); // File should have content
    }

    @Test
    void testGenerateReport_NoDiscrepancies() {
        // Setup test data
        ComparisonService.ComparisonSummary summary = createSummaryWithoutDiscrepancies();

        // Execute
        File reportFile = excelReportService.generateReport(summary);

        // Verify
        assertNotNull(reportFile);
        assertTrue(reportFile.exists());
        assertTrue(reportFile.getName().endsWith(".xlsx"));
        assertTrue(reportFile.length() > 0); // File should have content (summary sheet)
    }

    @Test
    void testGenerateReport_OutputDirectoryCreation() {
        // Setup with non-existent directory
        Path nonExistentDir = tempDir.resolve("reports").resolve("subfolder");
        when(reportConfig.getOutputDirectory()).thenReturn(nonExistentDir.toString());

        ComparisonService.ComparisonSummary summary = createSummaryWithoutDiscrepancies();

        // Execute
        File reportFile = excelReportService.generateReport(summary);

        // Verify
        assertNotNull(reportFile);
        assertTrue(reportFile.exists());
        assertTrue(reportFile.getParentFile().exists()); // Directory should be created
    }

    @Test
    void testGenerateReport_FileNameWithDate() {
        ComparisonService.ComparisonSummary summary = createSummaryWithoutDiscrepancies();

        // Execute
        File reportFile = excelReportService.generateReport(summary);

        // Verify filename contains the date
        String expectedDate = summary.getComparisonDate().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertTrue(reportFile.getName().contains(expectedDate));
    }

    @Test
    void testGenerateReport_InvalidOutputDirectory() {
        // Setup with invalid directory (file instead of directory)
        File invalidDir = tempDir.resolve("invalid.txt").toFile();
        try {
            invalidDir.createNewFile();
        } catch (Exception e) {
            // Ignore
        }
        
        when(reportConfig.getOutputDirectory()).thenReturn(invalidDir.getAbsolutePath());

        ComparisonService.ComparisonSummary summary = createSummaryWithoutDiscrepancies();

        // Execute and verify exception
        assertThrows(RuntimeException.class, () -> excelReportService.generateReport(summary));
    }

    private ComparisonService.ComparisonSummary createSummaryWithDiscrepancies() {
        ComparisonService.ComparisonSummary summary = new ComparisonService.ComparisonSummary();
        summary.setComparisonDate(LocalDate.of(2023, 12, 1));
        summary.setTotalDbRecords(10);
        summary.setTotalFixLogFiles(5);
        summary.setTotalFixMessages(8);
        
        List<ComparisonResult> discrepancies = new ArrayList<>();
        
        // Add sample discrepancies
        ComparisonResult discrepancy1 = new ComparisonResult();
        discrepancy1.setDoneNo("DONE001");
        discrepancy1.setExecId("EXEC001");
        discrepancy1.setField("PRICE");
        discrepancy1.setDatabaseValue("100.50");
        discrepancy1.setFixValue("100.51");
        discrepancy1.setDiscrepancyType(ComparisonResult.DiscrepancyType.VALUE_MISMATCH.name());
        discrepancy1.setSessionId("SESSION1");
        discrepancy1.setComparisonTime(LocalDateTime.now());
        discrepancies.add(discrepancy1);
        
        ComparisonResult discrepancy2 = new ComparisonResult();
        discrepancy2.setDoneNo("DONE002");
        discrepancy2.setExecId("EXEC002");
        discrepancy2.setField("QUANTITY");
        discrepancy2.setDatabaseValue("1000");
        discrepancy2.setFixValue("999");
        discrepancy2.setDiscrepancyType(ComparisonResult.DiscrepancyType.VALUE_MISMATCH.name());
        discrepancy2.setSessionId("SESSION2");
        discrepancy2.setComparisonTime(LocalDateTime.now());
        discrepancies.add(discrepancy2);
        
        summary.setDiscrepancies(discrepancies);
        summary.setTotalDiscrepancies(discrepancies.size());
        
        return summary;
    }

    private ComparisonService.ComparisonSummary createSummaryWithoutDiscrepancies() {
        ComparisonService.ComparisonSummary summary = new ComparisonService.ComparisonSummary();
        summary.setComparisonDate(LocalDate.of(2023, 12, 1));
        summary.setTotalDbRecords(10);
        summary.setTotalFixLogFiles(5);
        summary.setTotalFixMessages(10);
        summary.setTotalDiscrepancies(0);
        summary.setDiscrepancies(new ArrayList<>());
        
        return summary;
    }
}