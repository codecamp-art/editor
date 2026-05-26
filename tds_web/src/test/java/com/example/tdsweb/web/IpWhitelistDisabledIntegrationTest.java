package com.example.tdsweb.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {"tds.mode=stub", "app.security.enabled=false"})
@AutoConfigureMockMvc
class IpWhitelistDisabledIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void disabledSecurityDoesNotApplyIpWhitelist() throws Exception {
        mockMvc.perform(get("/api/tds/clients").param("query", "Alpha").with(remoteAddress("203.0.113.10")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].clientId").value("1001"));
    }

    private static RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
