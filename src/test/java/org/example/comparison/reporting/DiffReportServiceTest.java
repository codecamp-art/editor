package org.example.comparison.reporting;

import org.example.comparison.config.ComparisonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiffReportServiceTest {

    @Mock
    private ComparisonConfig comparisonConfig;

    @Mock
    private ComparisonConfig.ReportConfig reportConfig;

    @TempDir
    Path tempDir;

    private DiffReportService service;

    @BeforeEach
    void setUp() {
        when(comparisonConfig.getReport()).thenReturn(reportConfig);
        when(reportConfig.getOutputDirectory()).thenReturn(tempDir.toString());
        when(reportConfig.isGenerateJunit()).thenReturn(true);
        when(reportConfig.isGenerateHtml()).thenReturn(true);
        when(reportConfig.isGenerateJson()).thenReturn(true);
        when(reportConfig.getJunitSubdirectory()).thenReturn("junit");
        when(reportConfig.getHtmlSubdirectory()).thenReturn("html");
        when(reportConfig.getJsonSubdirectory()).thenReturn("json");
        when(reportConfig.getJunitFileName()).thenReturn("TEST-diff-report.xml");
        when(reportConfig.getHtmlFileName()).thenReturn("diff-report.html");
        when(reportConfig.getJsonFileName()).thenReturn("diff-report.json");
        when(reportConfig.getJunitSuiteName()).thenReturn("EnvironmentDiffs");
        service = new DiffReportService(comparisonConfig);
    }

    @Test
    void writesAllFormats() {
        DiffReportModels.DiffReport report = new DiffReportModels.DiffReport();
        report.envALabel = "A";
        report.envBLabel = "B";
        // create one diff in each to trigger failures in junit
        DiffReportModels.ConfigDiffRow c = new DiffReportModels.ConfigDiffRow();
        c.component = "comp";
        report.configDiffs = Collections.singletonList(c);
        DiffReportModels.BinaryDiffRow b = new DiffReportModels.BinaryDiffRow();
        b.component = "comp";
        report.binaryDiffs = Collections.singletonList(b);
        DiffReportModels.DbSchemaDiffRow d = new DiffReportModels.DbSchemaDiffRow();
        d.objectType = "TABLE";
        d.objectName = "T1";
        d.difference = "missing column";
        report.dbSchemaDiffs = Collections.singletonList(d);

        service.writeAll(report);

        assertTrue(new File(tempDir.toFile(), "junit/TEST-diff-report.xml").exists());
        assertTrue(new File(tempDir.toFile(), "html/diff-report.html").exists());
        assertTrue(new File(tempDir.toFile(), "json/diff-report.json").exists());
    }
}

