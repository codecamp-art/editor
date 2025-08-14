package org.example.comparison.reporting;

import org.example.comparison.config.ComparisonConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualReportPreviewTest {

    @Test
    void generateSampleReportToWorkspaceReportsDir() {
        // Configure outputs to ./reports
        ComparisonConfig cfg = new ComparisonConfig();
        ComparisonConfig.ReportConfig rc = new ComparisonConfig.ReportConfig();
        rc.setOutputDirectory("./reports");
        rc.setGenerateHtml(true);
        rc.setGenerateJunit(true);
        rc.setGenerateJson(true);
        cfg.setReport(rc);

        DiffReportService service = new DiffReportService(cfg);

        DiffReportModels.DiffReport report = new DiffReportModels.DiffReport();
        report.title = "Sample Environment Difference Report";
        report.envALabel = "Env A";
        report.envBLabel = "Env B";

        // Config diffs
        List<DiffReportModels.ConfigDiffRow> config = new ArrayList<>();
        DiffReportModels.ConfigDiffRow c1 = new DiffReportModels.ConfigDiffRow();
        c1.component = "OrderService"; c1.server = "app-01"; c1.envA = "A"; c1.envAHost = "a-host"; c1.envB = "B"; c1.envBHost = "b-host";
        c1.envAFile = "/etc/app/config.yaml"; c1.envBFile = "/etc/app/config.yaml"; c1.location = "db.pool.size"; c1.envAValue = "10"; c1.envBValue = "12";
        config.add(c1);
        DiffReportModels.ConfigDiffRow c2 = new DiffReportModels.ConfigDiffRow();
        c2.component = "Pricing"; c2.server = "app-02"; c2.envA = "A"; c2.envAHost = "a2"; c2.envB = "B"; c2.envBHost = "b2";
        c2.envAFile = "/etc/app/pricing.yaml"; c2.envBFile = "/etc/app/pricing.yaml"; c2.location = "currency"; c2.envAValue = "USD"; c2.envBValue = "HKD";
        config.add(c2);
        report.configDiffs = config;

        // Binary diffs
        List<DiffReportModels.BinaryDiffRow> bins = new ArrayList<>();
        DiffReportModels.BinaryDiffRow b1 = new DiffReportModels.BinaryDiffRow();
        b1.component = "OrderService"; b1.server = "app-01"; b1.envA = "A"; b1.envAHost = "a-host"; b1.envB = "B"; b1.envBHost = "b-host";
        b1.envAFile = "/opt/app/lib/module.jar"; b1.envBFile = "/opt/app/lib/module.jar"; b1.envAChecksum = "abc123"; b1.envBChecksum = "def456";
        bins.add(b1);
        report.binaryDiffs = bins;

        // DB schema diffs
        List<DiffReportModels.DbSchemaDiffRow> db = new ArrayList<>();
        DiffReportModels.DbSchemaDiffRow d1 = new DiffReportModels.DbSchemaDiffRow();
        d1.objectType = "TABLE"; d1.objectName = "ORDERS"; d1.difference = "Missing column PRICE in Env B"; d1.envAValue = "Has PRICE"; d1.envBValue = "No PRICE";
        db.add(d1);
        report.dbSchemaDiffs = db;

        // Excluded config
        List<DiffReportModels.ExcludedConfigRow> exclCfg = new ArrayList<>();
        DiffReportModels.ExcludedConfigRow ec1 = new DiffReportModels.ExcludedConfigRow();
        ec1.component = "OrderService"; ec1.server = "app-01"; ec1.envA = "A"; ec1.envAHost = "a-host"; ec1.envB = "B"; ec1.envBHost = "b-host";
        ec1.envAFile = "/etc/app/config.yaml"; ec1.envBFile = "/etc/app/config.yaml"; ec1.location = "secrets.apiKey"; ec1.envAValue = "***"; ec1.envBValue = "***";
        exclCfg.add(ec1);
        report.excludedConfig = exclCfg;

        // Excluded binary
        List<DiffReportModels.ExcludedBinaryRow> exclBin = new ArrayList<>();
        DiffReportModels.ExcludedBinaryRow eb1 = new DiffReportModels.ExcludedBinaryRow();
        eb1.component = "Pricing"; eb1.server = "app-02"; eb1.envA = "A"; eb1.envAHost = "a2"; eb1.envB = "B"; eb1.envBHost = "b2";
        eb1.envAFile = "/opt/app/lib/optional.jar"; eb1.envBFile = "/opt/app/lib/optional.jar";
        exclBin.add(eb1);
        report.excludedBinary = exclBin;

        service.writeAll(report);

        assertTrue(new File("reports/html/diff-report.html").exists());
    }
}

