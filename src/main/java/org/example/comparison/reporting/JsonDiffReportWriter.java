package org.example.comparison.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;

public class JsonDiffReportWriter {
    private final ObjectMapper mapper;

    public JsonDiffReportWriter() {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public File write(File outputDir, DiffReportModels.DiffReport report, String fileName) {
        try {
            if (!outputDir.exists()) outputDir.mkdirs();
            File out = new File(outputDir, fileName);
            mapper.writeValue(out, report);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Failed to write JSON diff report", e);
        }
    }
}

