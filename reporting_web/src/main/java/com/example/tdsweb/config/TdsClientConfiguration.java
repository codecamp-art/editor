package com.example.tdsweb.config;

import com.example.tdsweb.tds.NativeProcessTdsQueryClient;
import com.example.tdsweb.tds.NativeSdkValidator;
import com.example.tdsweb.tds.LocalConfigTdsPasswordProvider;
import com.example.tdsweb.tds.StubTdsQueryClient;
import com.example.tdsweb.tds.TdsPasswordProvider;
import com.example.tdsweb.tds.TdsQueryClient;
import com.example.tdsweb.tds.TdsRecordMapper;
import com.example.tdsweb.tds.VaultTdsPasswordProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TdsClientConfiguration {
    @Bean
    TdsRecordMapper tdsRecordMapper() {
        return new TdsRecordMapper();
    }

    @Bean
    @ConditionalOnProperty(name = "tds.mode", havingValue = "stub", matchIfMissing = true)
    TdsQueryClient stubTdsQueryClient(TdsRecordMapper mapper) {
        return new StubTdsQueryClient(mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "tds.mode", havingValue = "native")
    NativeSdkValidator nativeSdkValidator(TdsProperties properties) {
        return new NativeSdkValidator(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "tds.mode", havingValue = "native")
    TdsPasswordProvider tdsPasswordProvider(TdsProperties properties, ObjectMapper objectMapper) {
        return switch (properties.getPasswordSource()) {
            case VAULT -> new VaultTdsPasswordProvider(properties, objectMapper);
            case LOCAL_CONFIG -> {
                requireWindowsForLocalConfigPassword();
                yield new LocalConfigTdsPasswordProvider(properties);
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "tds.mode", havingValue = "native")
    TdsQueryClient nativeTdsQueryClient(
        TdsProperties properties,
        TdsRecordMapper mapper,
        NativeSdkValidator validator,
        ObjectMapper objectMapper,
        TdsPasswordProvider passwordProvider
    ) {
        validator.validate();
        return new NativeProcessTdsQueryClient(properties, mapper, objectMapper, passwordProvider);
    }

    private static void requireWindowsForLocalConfigPassword() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("windows")) {
            throw new IllegalStateException("tds.password-source=local-config is only supported for Windows local debug runs");
        }
    }
}
