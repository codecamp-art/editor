package com.example.tdsweb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IpWhitelistTest {
    @Test
    void matchesExactIpAndCidrRanges() {
        IpWhitelist whitelist = IpWhitelist.from(
            List.of("192.168.10.15", "10.20.30.0/24", "172.16.128.0/17", "2001:db8::/32")
        );

        assertThat(whitelist.contains("192.168.10.15")).isTrue();
        assertThat(whitelist.contains("10.20.30.45")).isTrue();
        assertThat(whitelist.contains("172.16.255.10")).isTrue();
        assertThat(whitelist.contains("2001:db8::100")).isTrue();
        assertThat(whitelist.contains("192.168.10.16")).isFalse();
        assertThat(whitelist.contains("10.20.31.45")).isFalse();
        assertThat(whitelist.contains("172.16.127.10")).isFalse();
        assertThat(whitelist.contains("2001:db9::100")).isFalse();
    }

    @Test
    void rejectsInvalidRemoteAddress() {
        IpWhitelist whitelist = IpWhitelist.from(List.of("127.0.0.1/32"));

        assertThat(whitelist.contains("not-an-ip-address")).isFalse();
    }
}
