package com.example.tdsweb.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class StaticUiAssetTest {
    @Test
    void uiContainsClientSearchSelectionAndCopyPayloadSupport() throws IOException {
        String html = read("static/index.html");
        String js = read("static/app.js");

        assertThat(html).contains("TDS Reporting System", "Dashboard", "Client TDS Query", "DOMAIN\\user");
        assertThat(html).contains("clientQuery", "candidateList", "summaryCard", "positionsCard", "copyButton");
        assertThat(html).contains("Query", "Reset", "Client Summary", "Positions");
        assertThat(js).contains("buildClipboardRows", "rowsToTsv", "ClipboardItem");
        assertThat(js).contains("Client ID", "Currency", "Margin Available", "Total Equity", "Risk Ratio (%)");
        assertThat(js).contains("Positions", "Total Long", "Total Short", "Intraday Long", "Intraday Short");
        assertThat(js).contains("selectedClient", "candidate-option", "setSelectedClient");
        assertThat(js).contains("/api/tds/clients?query=", "/api/tds/clients/");
    }

    private static String read(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
