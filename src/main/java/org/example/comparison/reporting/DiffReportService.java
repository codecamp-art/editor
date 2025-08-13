package org.example.comparison.reporting;

import org.example.comparison.config.ComparisonConfig;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;

@Service
public class DiffReportService {

    private final ComparisonConfig.ReportConfig reportConfig;
    private final JUnitDiffReportWriter junitWriter = new JUnitDiffReportWriter();
    private final HtmlDiffReportWriter htmlWriter = new HtmlDiffReportWriter();
    private final JsonDiffReportWriter jsonWriter = new JsonDiffReportWriter();

    public DiffReportService(ComparisonConfig comparisonConfig) {
        this.reportConfig = comparisonConfig.getReport();
    }

    public void writeAll(DiffReportModels.DiffReport report) {
        report.generatedAt = LocalDateTime.now();
        File baseDir = new File(reportConfig.getOutputDirectory());

        if (reportConfig.isGenerateJunit()) {
            File dir = new File(baseDir, reportConfig.getJunitSubdirectory());
            junitWriter.write(dir, reportConfig.getJunitSuiteName(), report, reportConfig.getJunitFileName());
        }
        if (reportConfig.isGenerateHtml()) {
            File dir = new File(baseDir, reportConfig.getHtmlSubdirectory());
            htmlWriter.write(dir, report, reportConfig.getHtmlFileName());
        }
        if (reportConfig.isGenerateJson()) {
            File dir = new File(baseDir, reportConfig.getJsonSubdirectory());
            jsonWriter.write(dir, report, reportConfig.getJsonFileName());
        }
    }
}

