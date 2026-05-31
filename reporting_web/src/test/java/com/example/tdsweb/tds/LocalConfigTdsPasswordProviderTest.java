package com.example.tdsweb.tds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.tdsweb.config.TdsProperties;
import org.junit.jupiter.api.Test;

class LocalConfigTdsPasswordProviderTest {
    @Test
    void readsPasswordFromLocalConfig() {
        TdsProperties properties = new TdsProperties();
        properties.setLocalPassword("local-debug-password");

        LocalConfigTdsPasswordProvider provider = new LocalConfigTdsPasswordProvider(properties);

        assertThat(provider.getPassword()).isEqualTo("local-debug-password");
    }

    @Test
    void requiresNonPlaceholderPassword() {
        TdsProperties properties = new TdsProperties();
        properties.setLocalPassword("REPLACE_ME_PASSWORD");

        LocalConfigTdsPasswordProvider provider = new LocalConfigTdsPasswordProvider(properties);

        assertThatThrownBy(provider::getPassword)
            .isInstanceOf(TdsClientException.class)
            .hasMessageContaining("tds.local-password is required");
    }
}
