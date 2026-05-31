package com.example.tdsweb.tds;

import com.example.tdsweb.config.TdsProperties;

public class LocalConfigTdsPasswordProvider implements TdsPasswordProvider {
    private final TdsProperties properties;

    public LocalConfigTdsPasswordProvider(TdsProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getPassword() {
        String password = properties.getLocalPassword();
        if (password == null || password.isBlank() || password.startsWith("REPLACE_ME")) {
            throw new TdsClientException("tds.local-password is required when tds.password-source=local-config");
        }
        return password;
    }
}
