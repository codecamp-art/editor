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
                        + "<style>body{font-family:Arial,Helvetica,sans-serif;margin:16px;}table{border-collapse:collapse;width:100%;margin-bottom:24px;}th,td{border:1px solid #ddd;padding:8px;}th{background:#f2f2f2;text-align:left;}h2{margin-top:32px;} .pass{color:green;} .fail{color:#b30000;} th input{width:100%;box-sizing:border-box;padding:4px;font-size:12px;}</style>"
                        + "<script>\n"
                        + "function setupFilters(id){var t=document.getElementById(id);if(!t)return;var inputs=t.querySelectorAll('thead tr.filters input');inputs.forEach(function(input){input.addEventListener('input',function(){applyFilter(t);});});}"
                        + "function applyFilter(t){var inputs=t.querySelectorAll('thead tr.filters input');var filters=Array.from(inputs).map(function(i){return i.value.toLowerCase();});var rows=t.querySelectorAll('tbody tr');rows.forEach(function(r){var cells=r.querySelectorAll('td');var show=true;for(var i=0;i<filters.length;i++){var f=filters[i];if(f&&cells[i]){var text=cells[i].textContent.toLowerCase();if(text.indexOf(f)===-1){show=false;break;}}}r.style.display=show?'':'none';});}"
                        + "document.addEventListener('DOMContentLoaded',function(){['configTable','binaryTable','dbTable','excludedConfigTable','excludedBinaryTable'].forEach(setupFilters);});\n"
                        + "</script>"
                        + "</head><body>");

                fw.write("<h1>Environment Diff Report</h1>");
                fw.write("<p>Generated: " + report.generatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "</p>");
                fw.write("<p>Env A: " + safe(report.envALabel) + " | Env B: " + safe(report.envBLabel) + "</p>");
                fw.write("<p>Status: " + (report.isPassing() ? "<span class=\"pass\">PASS</span>" : "<span class=\"fail\">FAIL</span>") + "</p>");

                // Config diffs
                fw.write("<h2>Config File Diffs</h2>");
                writeTableStart(fw, "configTable", new String[]{"Component","Server","Env A","Env A Host","Env B","Env B Host","Env A File","Env B File","Location","Env A Value","Env B Value"});
                if (report.configDiffs != null) {
                    for (DiffReportModels.ConfigDiffRow r : report.configDiffs) {
                        fw.write("<tr>" + td(r.component) + td(r.server) + td(r.envA) + td(r.envAHost) + td(r.envB) + td(r.envBHost)
                                + td(r.envAFile) + td(r.envBFile) + td(r.location) + td(r.envAValue) + td(r.envBValue) + "</tr>\n");
                    }
                }
                fw.write("</tbody></table>");

                // Binary diffs
                fw.write("<h2>Binary Checksum Diffs</h2>");
                writeTableStart(fw, "binaryTable", new String[]{"Component","Server","Env A","Env A Host","Env B","Env B Host","Env A File","Env B File","Env A checksum","Env B checksum"});
                if (report.binaryDiffs != null) {
                    for (DiffReportModels.BinaryDiffRow r : report.binaryDiffs) {
                        fw.write("<tr>" + td(r.component) + td(r.server) + td(r.envA) + td(r.envAHost) + td(r.envB) + td(r.envBHost)
                                + td(r.envAFile) + td(r.envBFile) + td(r.envAChecksum) + td(r.envBChecksum) + "</tr>\n");
                    }
                }
                fw.write("</tbody></table>");

                // DB schema diffs
                fw.write("<h2>Database Schema Diffs</h2>");
                writeTableStart(fw, "dbTable", new String[]{"Object Type","Object Name","Difference","Env A Value","Env B Value"});
                if (report.dbSchemaDiffs != null) {
                    for (DiffReportModels.DbSchemaDiffRow r : report.dbSchemaDiffs) {
                        fw.write("<tr>" + td(r.objectType) + td(r.objectName) + td(r.difference) + td(r.envAValue) + td(r.envBValue) + "</tr>\n");
                    }
                }
                fw.write("</tbody></table>");

                // Excluded config
                fw.write("<h2>Excluded Config Entries</h2>");
                writeTableStart(fw, "excludedConfigTable", new String[]{"Component","Server","Env A","Env A Host","Env B","Env B Host","Env A File","Env B File","Location","Env A Value","Env B Value"});
                if (report.excludedConfig != null) {
                    for (DiffReportModels.ExcludedConfigRow r : report.excludedConfig) {
                        fw.write("<tr>" + td(r.component) + td(r.server) + td(r.envA) + td(r.envAHost) + td(r.envB) + td(r.envBHost)
                                + td(r.envAFile) + td(r.envBFile) + td(r.location) + td(r.envAValue) + td(r.envBValue) + "</tr>\n");
                    }
                }
                fw.write("</tbody></table>");

                // Excluded binary
                fw.write("<h2>Excluded Binary Files</h2>");
                writeTableStart(fw, "excludedBinaryTable", new String[]{"Component","Server","Env A","Env A Host","Env B","Env B Host","Env A File","Env B File"});
                if (report.excludedBinary != null) {
                    for (DiffReportModels.ExcludedBinaryRow r : report.excludedBinary) {
                        fw.write("<tr>" + td(r.component) + td(r.server) + td(r.envA) + td(r.envAHost) + td(r.envB) + td(r.envBHost)
                                + td(r.envAFile) + td(r.envBFile) + "</tr>\n");
                    }
                }
                fw.write("</tbody></table>");

                fw.write("</body></html>");
            }

            return out;
        } catch (Exception e) {
            throw new RuntimeException("Failed to write HTML diff report", e);
        }
    }

    private void writeTableStart(FileWriter fw, String tableId, String[] headers) throws Exception {
        fw.write("<table id=\"" + safe(tableId) + "\"><thead><tr>");
        for (String h : headers) {
            fw.write("<th>" + safe(h) + "</th>");
        }
        fw.write("</tr><tr class=\"filters\">");
        for (int i = 0; i < headers.length; i++) {
            fw.write("<th><input type=\"text\" placeholder=\"Filter...\" /></th>");
        }
        fw.write("</tr></thead><tbody>\n");
    }

    private String td(String v) {
        return "<td>" + safe(v) + "</td>";
    }

    private String safe(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

