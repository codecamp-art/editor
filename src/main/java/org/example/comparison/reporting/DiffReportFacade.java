package org.example.comparison.reporting;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DiffReportFacade {

    private final DiffReportService diffReportService;

    public DiffReportFacade(DiffReportService diffReportService) {
        this.diffReportService = diffReportService;
    }

    public void generateReports(String envALabel,
                                String envBLabel,
                                List<DiffReportModels.ConfigDiffRow> configDiffs,
                                List<DiffReportModels.BinaryDiffRow> binaryDiffs,
                                List<DiffReportModels.DbSchemaDiffRow> dbSchemaDiffs,
                                List<DiffReportModels.ExcludedConfigRow> excludedConfig,
                                List<DiffReportModels.ExcludedBinaryRow> excludedBinary) {
        DiffReportModels.DiffReport report = new DiffReportModels.DiffReport();
        report.title = "Environment Difference Report";
        report.envALabel = envALabel;
        report.envBLabel = envBLabel;
        report.configDiffs = configDiffs;
        report.binaryDiffs = binaryDiffs;
        report.dbSchemaDiffs = dbSchemaDiffs;
        report.excludedConfig = excludedConfig;
        report.excludedBinary = excludedBinary;
        diffReportService.writeAll(report);
    }
}

