package com.example.tdsweb.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "tds.mode=stub")
@AutoConfigureMockMvc
class StubModeIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void stubModeReturnsDeterministicDataWithoutVendorRuntime() throws Exception {
        mockMvc.perform(get("/api/tds/clients").param("query", "Alpha").with(user("tester")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].clientId").value("1001"));

        mockMvc.perform(get("/api/tds/clients/1001").with(user("tester")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.summary.totalEquity").value(100000000.00))
            .andExpect(jsonPath("$.positions[0].position").value("ag2703"))
            .andExpect(jsonPath("$.positions[5].position").value("b2610"));
    }
}
