package org.example.comparison.reporting;

import java.io.File;
import java.io.FileWriter;
import java.time.format.DateTimeFormatter;

public class HtmlDiffReportWriter {

    public File write(File outputDir, DiffReportModels.DiffReport report, String fileName) {
        try {
            if (!outputDir.exists()) outputDir.mkdirs();

            File out = new File(outputDir, fileName);
            try (FileWriter fw = new FileWriter(out)) {
                fw.write("<html><head><meta charset=\"utf-8\"><title>Diff Report</title>"
                        + "<style>body{font-family:Arial,Helvetica,sans-serif;margin:16px;}table{border-collapse:collapse;width:100%;margin-bottom:24px;}th,td{border:1px solid #ddd;padding:8px;}th{background:#f2f2f2;text-align:left;}h2{margin-top:32px;} .pass{color:green;} .fail{color:#b30000;}</style>"
                        + "</head><body>");

                fw.write("<h1>Environment Diff Report</h1>");
                fw.write("<p>Generated: " + report.generatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "</p>");
                fw.write("<p>Env A: " + safe(report.envALabel) + " | Env B: " + safe(report.envBLabel) + "</p>");
                fw.write("<p>Status: " + (report.isPassing() ? "<span class=\"pass\">PASS</span>" : "<span class=\"fail\">FAIL</span>") + "</p>");

                // Config diffs
                fw.write("<h2>Config File Diffs</h2>");
                writeTableHeader(fw, new String[]{"Component","Server","Env A","Env A Host","Env B","Env B Host","Env A File","Env B File","Location","Env A Value","Env B Value"});
                if (report.configDiffs != null) {
                    for (DiffReportModels.ConfigDiffRow r : report.configDiffs) {
                        fw.write("<tr>" + td(r.component) + td(r.server) + td(r.envA) + td(r.envAHost) + td(r.envB) + td(r.envBHost)
                                + td(r.envAFile) + td(r.envBFile) + td(r.location) + td(r.envAValue) + td(r.envBValue) + "</tr>\n");
                    }
                }
                fw.write("</table>");

                // Binary diffs
                fw.write("<h2>Binary Checksum Diffs</h2>");
                writeTableHeader(fw, new String[]{"Component","Server","Env A","Env A Host","Env B","Env B Host","Env A File","Env B File","Env A checksum","Env B checksum"});
                if (report.binaryDiffs != null) {
                    for (DiffReportModels.BinaryDiffRow r : report.binaryDiffs) {
                        fw.write("<tr>" + td(r.component) + td(r.server) + td(r.envA) + td(r.envAHost) + td(r.envB) + td(r.envBHost)
                                + td(r.envAFile) + td(r.envBFile) + td(r.envAChecksum) + td(r.envBChecksum) + "</tr>\n");
                    }
                }
                fw.write("</table>");

                // DB schema diffs
                fw.write("<h2>Database Schema Diffs</h2>");
                writeTableHeader(fw, new String[]{"Object Type","Object Name","Difference","Env A Value","Env B Value"});
                if (report.dbSchemaDiffs != null) {
                    for (DiffReportModels.DbSchemaDiffRow r : report.dbSchemaDiffs) {
                        fw.write("<tr>" + td(r.objectType) + td(r.objectName) + td(r.difference) + td(r.envAValue) + td(r.envBValue) + "</tr>\n");
                    }
                }
                fw.write("</table>");

                // Excluded config
                fw.write("<h2>Excluded Config Entries</h2>");
                writeTableHeader(fw, new String[]{"Component","Server","Env A","Env A Host","Env B","Env B Host","Env A File","Env B File","Location","Env A Value","Env B Value"});
                if (report.excludedConfig != null) {
                    for (DiffReportModels.ExcludedConfigRow r : report.excludedConfig) {
                        fw.write("<tr>" + td(r.component) + td(r.server) + td(r.envA) + td(r.envAHost) + td(r.envB) + td(r.envBHost)
                                + td(r.envAFile) + td(r.envBFile) + td(r.location) + td(r.envAValue) + td(r.envBValue) + "</tr>\n");
                    }
                }
                fw.write("</table>");

                // Excluded binary
                fw.write("<h2>Excluded Binary Files</h2>");
                writeTableHeader(fw, new String[]{"Component","Server","Env A","Env A Host","Env B","Env B Host","Env A File","Env B File"});
                if (report.excludedBinary != null) {
                    for (DiffReportModels.ExcludedBinaryRow r : report.excludedBinary) {
                        fw.write("<tr>" + td(r.component) + td(r.server) + td(r.envA) + td(r.envAHost) + td(r.envB) + td(r.envBHost)
                                + td(r.envAFile) + td(r.envBFile) + "</tr>\n");
                    }
                }
                fw.write("</table>");

                fw.write("</body></html>");
            }

            return out;
        } catch (Exception e) {
            throw new RuntimeException("Failed to write HTML diff report", e);
        }
    }

    private void writeTableHeader(FileWriter fw, String[] headers) throws Exception {
        fw.write("<table><tr>");
        for (String h : headers) {
            fw.write("<th>" + safe(h) + "</th>");
        }
        fw.write("</tr>\n");
    }

    private String td(String v) {
        return "<td>" + safe(v) + "</td>";
    }

    private String safe(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

