package com.example.tdsweb.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.tdsweb.domain.ClientCandidate;
import com.example.tdsweb.domain.ClientQueryResult;
import com.example.tdsweb.domain.ClientSummary;
import com.example.tdsweb.domain.Position;
import com.example.tdsweb.tds.TdsClientException;
import com.example.tdsweb.tds.TdsQueryClient;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class TdsClientControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TdsQueryClient tdsQueryClient;

    @Test
    void searchesClients() throws Exception {
        when(tdsQueryClient.searchClients("Alpha"))
            .thenReturn(List.of(new ClientCandidate("1001", "Alpha Capital")));

        mockMvc.perform(get("/api/tds/clients").param("query", "Alpha"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].clientId").value("1001"))
            .andExpect(jsonPath("$[0].clientName").value("Alpha Capital"));
    }

    @Test
    void queriesSelectedClientDetail() throws Exception {
        when(tdsQueryClient.queryClient("1001", Optional.of(20260418))).thenReturn(sampleResult());

        mockMvc.perform(get("/api/tds/clients/1001").param("tradeDate", "20260418"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.summary.currency").value("CNY"))
            .andExpect(jsonPath("$.positions[0].position").value("ag2703"));
    }

    @Test
    void rejectsInvalidLookupQuery() throws Exception {
        mockMvc.perform(get("/api/tds/clients").param("query", " "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("query is required"));

        verify(tdsQueryClient, never()).searchClients(any());
    }

    @Test
    void rejectsInvalidTradeDate() throws Exception {
        mockMvc.perform(get("/api/tds/clients/1001").param("tradeDate", "20260231"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("tradeDate must be a valid date"));

        verify(tdsQueryClient, never()).queryClient(any(), any());
    }

    @Test
    void rejectsRequestsOutsideIpWhitelist() throws Exception {
        mockMvc.perform(get("/api/tds/clients").param("query", "1001").with(remoteAddress("203.0.113.10")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("request IP is not allowed"));

        verify(tdsQueryClient, never()).searchClients(any());
    }

    @Test
    void sanitizesNativeFailure() throws Exception {
        when(tdsQueryClient.queryClient("1001", Optional.empty()))
            .thenThrow(new TdsClientException("TdsApi_reqLogin failed password=secret token=abc"));

        mockMvc.perform(get("/api/tds/clients/1001"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.message", containsString("password=<redacted>")))
            .andExpect(jsonPath("$.message", containsString("token=<redacted>")));
    }

    private static RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static ClientQueryResult sampleResult() {
        ClientSummary summary = new ClientSummary(
            "1001",
            "Alpha Capital",
            "FA1001",
            "CNY",
            new BigDecimal("100000000.00"),
            new BigDecimal("100000000.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
        return new ClientQueryResult(
            "1001",
            20260418,
            summary,
            List.of(new Position("ag2703", "CNY", 0, 100, 0, 0))
        );
    }
}
