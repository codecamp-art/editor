package com.example.tdsweb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

class SecurityConfigurationTest {
    @Test
    void grantsReportingAuthorityWhenOidcGroupIsAllowed() {
        Set<GrantedAuthority> authorities = SecurityConfiguration.reportingAuthorities(
            List.of(oidcAuthority("acl-uireport-qa-ro")),
            Set.of("acl-uireport-qa-ro")
        );

        assertThat(authorities)
            .extracting(GrantedAuthority::getAuthority)
            .contains(SecurityConfiguration.REPORTING_VIEW_AUTHORITY);
    }

    @Test
    void doesNotGrantReportingAuthorityWhenOidcGroupIsNotAllowed() {
        Set<GrantedAuthority> authorities = SecurityConfiguration.reportingAuthorities(
            List.of(oidcAuthority("some-other-group")),
            Set.of("acl-uireport-prod-ro")
        );

        assertThat(authorities)
            .extracting(GrantedAuthority::getAuthority)
            .doesNotContain(SecurityConfiguration.REPORTING_VIEW_AUTHORITY);
    }

    private static OidcUserAuthority oidcAuthority(String group) {
        Instant issuedAt = Instant.parse("2026-08-12T00:00:00Z");
        OidcIdToken idToken = new OidcIdToken(
            "token",
            issuedAt,
            issuedAt.plusSeconds(600),
            Map.of(
                IdTokenClaimNames.SUB, "user1",
                "groups", List.of(group)
            )
        );
        return new OidcUserAuthority(idToken);
    }
}
