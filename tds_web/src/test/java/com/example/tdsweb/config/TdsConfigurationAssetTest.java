package com.example.tdsweb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class TdsConfigurationAssetTest {
    @Test
    void yamlDoesNotConfigureTdsPasswordDirectly() throws IOException {
        assertNoDirectPassword("application.yml");
        assertNoDirectPassword("application-qa.yml");
        assertNoDirectPassword("application-prod.yml");
    }

    private static void assertNoDirectPassword(String path) throws IOException {
        String yaml = read(path);
        assertThat(yaml).doesNotContain("TDS_PASSWORD", "tds.password", "\n  password:");
        assertThat(yaml).contains("vault:");
    }

    private static String read(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
