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
    private Oidc oidc = new Oidc();

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

    public Oidc getOidc() {
        return oidc;
    }

    public void setOidc(Oidc oidc) {
        this.oidc = oidc == null ? new Oidc() : oidc;
    }

    public static class Oidc {
        private boolean enabled = false;
        private String registrationId = "reporting-web";
        private String sslBundle = "pingfed-mtls";
        private List<@NotBlank String> allowedGroups = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRegistrationId() {
            return registrationId;
        }

        public void setRegistrationId(String registrationId) {
            this.registrationId = registrationId;
        }

        public String getSslBundle() {
            return sslBundle;
        }

        public void setSslBundle(String sslBundle) {
            this.sslBundle = sslBundle;
        }

        public List<String> getAllowedGroups() {
            return allowedGroups;
        }

        public void setAllowedGroups(List<String> allowedGroups) {
            this.allowedGroups = allowedGroups == null ? new ArrayList<>() : allowedGroups;
        }
    }
}
