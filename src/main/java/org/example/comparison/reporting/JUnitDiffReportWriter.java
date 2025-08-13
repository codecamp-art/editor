package org.example.comparison.reporting;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileOutputStream;

public class JUnitDiffReportWriter {

    public File write(File outputDir, String suiteName, DiffReportModels.DiffReport report, String fileName) {
        try {
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.newDocument();

            Element testsuite = doc.createElement("testsuite");
            testsuite.setAttribute("name", suiteName);

            int tests = 3; // config, binary, db schema
            int failures = 0;
            if (report.configDiffs != null && !report.configDiffs.isEmpty()) failures++;
            if (report.binaryDiffs != null && !report.binaryDiffs.isEmpty()) failures++;
            if (report.dbSchemaDiffs != null && !report.dbSchemaDiffs.isEmpty()) failures++;

            testsuite.setAttribute("tests", String.valueOf(tests));
            testsuite.setAttribute("failures", String.valueOf(failures));
            testsuite.setAttribute("errors", "0");

            doc.appendChild(testsuite);

            addTestCase(doc, testsuite, "Config file diffs", report.configDiffs == null || report.configDiffs.isEmpty(),
                    "Config differences detected (" + (report.configDiffs == null ? 0 : report.configDiffs.size()) + ")");
            addTestCase(doc, testsuite, "Binary checksum diffs", report.binaryDiffs == null || report.binaryDiffs.isEmpty(),
                    "Binary differences detected (" + (report.binaryDiffs == null ? 0 : report.binaryDiffs.size()) + ")");
            addTestCase(doc, testsuite, "Database schema diffs", report.dbSchemaDiffs == null || report.dbSchemaDiffs.isEmpty(),
                    "DB schema differences detected (" + (report.dbSchemaDiffs == null ? 0 : report.dbSchemaDiffs.size()) + ")");

            File out = new File(outputDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                TransformerFactory tf = TransformerFactory.newInstance();
                Transformer transformer = tf.newTransformer();
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
                transformer.transform(new DOMSource(doc), new StreamResult(fos));
            }

            return out;
        } catch (Exception e) {
            throw new RuntimeException("Failed to write JUnit diff report", e);
        }
    }

    private void addTestCase(Document doc, Element testsuite, String name, boolean passing, String failureMessage) {
        Element testcase = doc.createElement("testcase");
        testcase.setAttribute("name", name);
        if (!passing) {
            Element failure = doc.createElement("failure");
            failure.setAttribute("message", failureMessage);
            testcase.appendChild(failure);
        }
        testsuite.appendChild(testcase);
    }
}

