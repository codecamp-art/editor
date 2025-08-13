package org.example.comparison.reporting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiffReportModels {

    public static class ConfigDiffRow {
        public String component;
        public String server;
        @JsonProperty("envA")
        public String envA;
        public String envAHost;
        @JsonProperty("envB")
        public String envB;
        public String envBHost;
        public String envAFile;
        public String envBFile;
        public String location;
        public String envAValue;
        public String envBValue;
    }

    public static class BinaryDiffRow {
        public String component;
        public String server;
        public String envA;
        public String envAHost;
        public String envB;
        public String envBHost;
        public String envAFile;
        public String envBFile;
        public String envAChecksum;
        public String envBChecksum;
    }

    public static class DbSchemaDiffRow {
        public String objectType; // TABLE | PROCEDURE | VIEW | INDEX | SEQUENCE
        public String objectName;
        public String difference; // human-readable diff summary
        public String envAValue; // optional representation
        public String envBValue; // optional representation
    }

    public static class ExcludedConfigRow {
        public String component;
        public String server;
        public String envA;
        public String envAHost;
        public String envB;
        public String envBHost;
        public String envAFile;
        public String envBFile;
        public String location;
        public String envAValue;
        public String envBValue;
    }

    public static class ExcludedBinaryRow {
        public String component;
        public String server;
        public String envA;
        public String envAHost;
        public String envB;
        public String envBHost;
        public String envAFile;
        public String envBFile;
    }

    public static class DiffReport {
        public String title;
        public LocalDateTime generatedAt;
        public String envALabel;
        public String envBLabel;
        public List<ConfigDiffRow> configDiffs;
        public List<BinaryDiffRow> binaryDiffs;
        public List<DbSchemaDiffRow> dbSchemaDiffs;
        public List<ExcludedConfigRow> excludedConfig;
        public List<ExcludedBinaryRow> excludedBinary;

        public boolean isPassing() {
            boolean hasConfig = configDiffs != null && !configDiffs.isEmpty();
            boolean hasBinary = binaryDiffs != null && !binaryDiffs.isEmpty();
            boolean hasDb = dbSchemaDiffs != null && !dbSchemaDiffs.isEmpty();
            return !(hasConfig || hasBinary || hasDb);
        }
    }
}

