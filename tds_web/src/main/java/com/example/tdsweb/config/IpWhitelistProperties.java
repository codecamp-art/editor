package com.example.tdsweb.config;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security")
public class IpWhitelistProperties {
    private boolean enabled = true;
    private List<@NotBlank String> allowedIpRanges = new ArrayList<>(List.of("127.0.0.1/32", "::1/128"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedIpRanges() {
        return allowedIpRanges;
    }

    public void setAllowedIpRanges(List<String> allowedIpRanges) {
        this.allowedIpRanges = allowedIpRanges == null ? new ArrayList<>() : allowedIpRanges;
    }
}
