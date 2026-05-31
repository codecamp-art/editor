package com.example.tdsweb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TdsPropertiesBindingTest {
    @Test
    void bindsLocalConfigPasswordSourceFromKebabCaseYamlName() {
        TdsProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
            "tds.password-source", "local-config",
            "tds.local-password", "local-debug-password"
        )))
            .bind("tds", Bindable.of(TdsProperties.class))
            .get();

        assertThat(properties.getPasswordSource()).isEqualTo(TdsProperties.PasswordSource.LOCAL_CONFIG);
        assertThat(properties.getLocalPassword()).isEqualTo("local-debug-password");
    }
}
