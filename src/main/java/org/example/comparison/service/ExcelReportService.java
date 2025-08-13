package org.example.comparison.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.comparison.config.ComparisonConfig;
import org.example.comparison.domain.ComparisonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for generating Excel reports of comparison discrepancies
 */
@Service
public class ExcelReportService {

    private static final Logger logger = LoggerFactory.getLogger(ExcelReportService.class);
    
    private final ComparisonConfig.ReportConfig reportConfig;

    private static final String[] DISCREPANCY_DETAILS_HEADERS = {
            "DONE_NO", "EXEC_ID", "Field", "Database Value", "FIX Value",
            "Discrepancy Type", "Session ID", "Comparison Time"
    };

    public ExcelReportService(ComparisonConfig comparisonConfig) {
        this.reportConfig = comparisonConfig.getReport();
    }

    /**
     * Generates Excel report for comparison discrepancies
     */
    public File generateReport(ComparisonService.ComparisonSummary summary) {
        logger.info("Generating Excel report for {} discrepancies", summary.getTotalDiscrepancies());
        
        try {
            // Ensure output directory exists
            File outputDir = new File(reportConfig.getOutputDirectory());
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            // Create filename with date
            String filename = reportConfig.getExcelFileName()
                    .replace("{date}", summary.getComparisonDate().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            File reportFile = new File(outputDir, filename);

            // Create workbook and generate report
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                createSummarySheet(workbook, summary);
                
                if (summary.hasDiscrepancies()) {
                    createDiscrepancyDetailsSheet(workbook, summary.getDiscrepancies());
                    createReferenceSheet(workbook);
                    createDiscrepancyByTypeSheet(workbook, summary.getDiscrepancies());
                }

                // Write to file
                try (FileOutputStream fileOut = new FileOutputStream(reportFile)) {
                    workbook.write(fileOut);
                }
            }

            logger.info("Excel report generated successfully: {}", reportFile.getAbsolutePath());
            
            // Clean up old reports if configured
            if (reportConfig.isDeleteOldReports()) {
                cleanupOldReports(outputDir);
            }

            return reportFile;

        } catch (Exception e) {
            logger.error("Error generating Excel report", e);
            throw new RuntimeException("Failed to generate Excel report", e);
        }
    }

    /**
     * Creates summary sheet with overview information
     */
    private void createSummarySheet(Workbook workbook, ComparisonService.ComparisonSummary summary) {
        Sheet sheet = workbook.createSheet("Summary");
        
        // Create styles
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle titleStyle = createTitleStyle(workbook);

        int rowNum = 0;

        // Title
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("FIX Log Comparison Report");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        rowNum++; // Empty row

        // Summary data
        addSummaryRow(sheet, rowNum++, "Comparison Date:", 
                     summary.getComparisonDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), 
                     headerStyle, dataStyle);
        addSummaryRow(sheet, rowNum++, "Total Database Records:", 
                     String.valueOf(summary.getTotalDbRecords()), headerStyle, dataStyle);
        addSummaryRow(sheet, rowNum++, "Total FIX Log Files:", 
                     String.valueOf(summary.getTotalFixLogFiles()), headerStyle, dataStyle);
        addSummaryRow(sheet, rowNum++, "Total FIX Messages:", 
                     String.valueOf(summary.getTotalFixMessages()), headerStyle, dataStyle);
        addSummaryRow(sheet, rowNum++, "Total Discrepancies:", 
                     String.valueOf(summary.getTotalDiscrepancies()), headerStyle, dataStyle);

        if (summary.hasError()) {
            rowNum++; // Empty row
            addSummaryRow(sheet, rowNum++, "Error:", summary.getErrorMessage(), headerStyle, dataStyle);
        }

        // Status
        rowNum++; // Empty row
        String status = summary.hasDiscrepancies() ? "DISCREPANCIES FOUND" : "NO DISCREPANCIES";
        addSummaryRow(sheet, rowNum++, "Status:", status, headerStyle, dataStyle);

        // Auto-size columns
        for (int i = 0; i < 4; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    /**
     * Creates detailed discrepancy sheet
     */
    private void createDiscrepancyDetailsSheet(Workbook workbook, List<ComparisonResult> discrepancies) {
        Sheet sheet = workbook.createSheet("Discrepancy Details");
        
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);

        // Create header row
        Row headerRow = sheet.createRow(0);
        String[] headers = DISCREPANCY_DETAILS_HEADERS;

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Add data rows
        int rowNum = 1;
        for (ComparisonResult discrepancy : discrepancies) {
            Row row = sheet.createRow(rowNum++);
            
            row.createCell(0).setCellValue(discrepancy.getDoneNo());
            row.createCell(1).setCellValue(discrepancy.getExecId());
            row.createCell(2).setCellValue(discrepancy.getField());
            row.createCell(3).setCellValue(discrepancy.getDatabaseValue());
            row.createCell(4).setCellValue(discrepancy.getFixValue());
            row.createCell(5).setCellValue(discrepancy.getDiscrepancyType());
            row.createCell(6).setCellValue(discrepancy.getSessionId());
            row.createCell(7).setCellValue(discrepancy.getComparisonTime().toString());
            
            // Apply style to all cells
            for (int i = 0; i < headers.length; i++) {
                if (row.getCell(i) != null) {
                    row.getCell(i).setCellStyle(dataStyle);
                }
            }
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        // Add filter
        sheet.setAutoFilter(new CellRangeAddress(0, rowNum - 1, 0, headers.length - 1));
    }

    /**
     * Creates a reference sheet with Chinese translations for each column in the Discrepancy Details sheet
     */
    private void createReferenceSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Reference");

        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);

        // Header
        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("Column (Details Sheet)");
        headerRow.createCell(1).setCellValue("中文翻译");
        headerRow.getCell(0).setCellStyle(headerStyle);
        headerRow.getCell(1).setCellStyle(headerStyle);

        // Translations aligned with DISCREPANCY_DETAILS_HEADERS
        String[] chineseTranslations = new String[] {
                "成交编号",      // DONE_NO
                "执行ID",        // EXEC_ID
                "字段",          // Field
                "数据库值",      // Database Value
                "FIX值",        // FIX Value
                "差异类型",      // Discrepancy Type
                "会话ID",        // Session ID
                "比对时间"       // Comparison Time
        };

        int rowNum = 1;
        for (int i = 0; i < DISCREPANCY_DETAILS_HEADERS.length; i++) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(DISCREPANCY_DETAILS_HEADERS[i]);
            row.createCell(1).setCellValue(chineseTranslations[i]);
            row.getCell(0).setCellStyle(dataStyle);
            row.getCell(1).setCellStyle(dataStyle);
        }

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    /**
     * Creates discrepancy summary by type sheet
     */
    private void createDiscrepancyByTypeSheet(Workbook workbook, List<ComparisonResult> discrepancies) {
        Sheet sheet = workbook.createSheet("Discrepancy Summary");
        
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);

        // Group discrepancies by type
        Map<String, Long> discrepancyByType = discrepancies.stream()
                .collect(Collectors.groupingBy(
                        ComparisonResult::getDiscrepancyType,
                        Collectors.counting()));

        // Group discrepancies by field
        Map<String, Long> discrepancyByField = discrepancies.stream()
                .collect(Collectors.groupingBy(
                        ComparisonResult::getField,
                        Collectors.counting()));

        int rowNum = 0;

        // Discrepancies by Type section
        Row typeHeaderRow = sheet.createRow(rowNum++);
        typeHeaderRow.createCell(0).setCellValue("Discrepancy Type");
        typeHeaderRow.createCell(1).setCellValue("Count");
        typeHeaderRow.getCell(0).setCellStyle(headerStyle);
        typeHeaderRow.getCell(1).setCellStyle(headerStyle);

        for (Map.Entry<String, Long> entry : discrepancyByType.entrySet()) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(entry.getKey());
            row.createCell(1).setCellValue(entry.getValue());
            row.getCell(0).setCellStyle(dataStyle);
            row.getCell(1).setCellStyle(dataStyle);
        }

        rowNum++; // Empty row

        // Discrepancies by Field section
        Row fieldHeaderRow = sheet.createRow(rowNum++);
        fieldHeaderRow.createCell(0).setCellValue("Field Name");
        fieldHeaderRow.createCell(1).setCellValue("Count");
        fieldHeaderRow.getCell(0).setCellStyle(headerStyle);
        fieldHeaderRow.getCell(1).setCellStyle(headerStyle);

        for (Map.Entry<String, Long> entry : discrepancyByField.entrySet()) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(entry.getKey());
            row.createCell(1).setCellValue(entry.getValue());
            row.getCell(0).setCellStyle(dataStyle);
            row.getCell(1).setCellStyle(dataStyle);
        }

        // Auto-size columns
        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    /**
     * Adds a summary row with label and value
     */
    private void addSummaryRow(Sheet sheet, int rowNum, String label, String value, 
                              CellStyle labelStyle, CellStyle valueStyle) {
        Row row = sheet.createRow(rowNum);
        
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(labelStyle);
        
        Cell valueCell = row.createCell(1);
        valueCell.setCellValue(value);
        valueCell.setCellStyle(valueStyle);
    }

    /**
     * Creates header cell style
     */
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    /**
     * Creates data cell style
     */
    private CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setWrapText(true);
        return style;
    }

    /**
     * Creates title cell style
     */
    private CellStyle createTitleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    /**
     * Cleans up old report files
     */
    private void cleanupOldReports(File outputDir) {
        try {
            File[] files = outputDir.listFiles((dir, name) -> name.endsWith(".xlsx"));
            if (files == null) return;

            long cutoffTime = System.currentTimeMillis() - 
                             (reportConfig.getKeepReportsForDays() * 24L * 60L * 60L * 1000L);

            for (File file : files) {
                if (file.lastModified() < cutoffTime) {
                    if (file.delete()) {
                        logger.info("Deleted old report file: {}", file.getName());
                    } else {
                        logger.warn("Failed to delete old report file: {}", file.getName());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error cleaning up old reports", e);
        }
    }
}