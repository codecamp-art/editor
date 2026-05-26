package com.example.tdsweb.tds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.tdsweb.config.TdsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class VaultTdsPasswordProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsKvV2PasswordThroughKerberosVaultLogin() {
        TdsProperties.Vault vault = configuredVault();
        FakeVaultHttpClient httpClient = new FakeVaultHttpClient()
            .expect(
                "POST",
                URI.create("https://vault.example/v1/auth/kerberos/login"),
                Map.of("X-Vault-Namespace", "tds/ns"),
                true,
                new VaultHttpResponse(200, "{\"auth\":{\"client_token\":\"vault-token\"}}")
            )
            .expect(
                "GET",
                URI.create("https://vault.example/v1/secret/data/tds/qa"),
                Map.of("X-Vault-Namespace", "tds/ns", "X-Vault-Token", "vault-token"),
                false,
                new VaultHttpResponse(200, "{\"data\":{\"data\":{\"password\":\"secret-from-vault\"}}}")
            );

        VaultTdsPasswordProvider provider = new VaultTdsPasswordProvider(vault, objectMapper, httpClient);

        assertThat(provider.getPassword()).isEqualTo("secret-from-vault");
        assertThat(provider.getPassword()).isEqualTo("secret-from-vault");
        assertThat(httpClient.pendingRequests()).isZero();
    }

    @Test
    void requiresVaultSecretPathForNativePassword() {
        TdsProperties.Vault vault = configuredVault();
        vault.setSecretPath("");

        VaultTdsPasswordProvider provider =
            new VaultTdsPasswordProvider(vault, objectMapper, new FakeVaultHttpClient());

        assertThatThrownBy(provider::getPassword)
            .isInstanceOf(TdsClientException.class)
            .hasMessageContaining("VAULT_SECRET_PATH or tds.vault.secret-path is required");
    }

    @Test
    void vaultFailuresDoNotExposeTokenOrResponseBody() {
        TdsProperties.Vault vault = configuredVault();
        FakeVaultHttpClient httpClient = new FakeVaultHttpClient()
            .expect(
                "POST",
                URI.create("https://vault.example/v1/auth/kerberos/login"),
                Map.of("X-Vault-Namespace", "tds/ns"),
                true,
                new VaultHttpResponse(200, "{\"auth\":{\"client_token\":\"vault-token\"}}")
            )
            .expect(
                "GET",
                URI.create("https://vault.example/v1/secret/data/tds/qa"),
                Map.of("X-Vault-Namespace", "tds/ns", "X-Vault-Token", "vault-token"),
                false,
                new VaultHttpResponse(500, "{\"data\":{\"data\":{\"password\":\"secret-from-vault\"}}}")
            );

        VaultTdsPasswordProvider provider = new VaultTdsPasswordProvider(vault, objectMapper, httpClient);

        assertThatThrownBy(provider::getPassword)
            .isInstanceOf(TdsClientException.class)
            .hasMessageContaining("HTTP status 500")
            .hasMessageNotContaining("vault-token")
            .hasMessageNotContaining("secret-from-vault")
            .hasMessageNotContaining("X-Vault-Token");
    }

    private static TdsProperties.Vault configuredVault() {
        TdsProperties.Vault vault = new TdsProperties.Vault();
        vault.setAddress("https://vault.example/");
        vault.setNamespace("tds/ns");
        vault.setSecretEngine("secret");
        vault.setSecretPath("tds/qa");
        vault.setSecretKey("password");
        return vault;
    }

    private static class FakeVaultHttpClient implements VaultHttpClient {
        private final Queue<ExpectedRequest> expectedRequests = new ArrayDeque<>();

        FakeVaultHttpClient expect(
            String method,
            URI uri,
            Map<String, String> headers,
            boolean kerberosNegotiate,
            VaultHttpResponse response
        ) {
            expectedRequests.add(new ExpectedRequest(method, uri, headers, kerberosNegotiate, response));
            return this;
        }

        int pendingRequests() {
            return expectedRequests.size();
        }

        @Override
        public VaultHttpResponse request(String method, URI uri, Map<String, String> headers, boolean kerberosNegotiate)
            throws IOException {
            ExpectedRequest expected = expectedRequests.remove();
            assertThat(method).isEqualTo(expected.method());
            assertThat(uri).isEqualTo(expected.uri());
            assertThat(new LinkedHashMap<>(headers)).containsExactlyInAnyOrderEntriesOf(expected.headers());
            assertThat(kerberosNegotiate).isEqualTo(expected.kerberosNegotiate());
            return expected.response();
        }
    }

    private record ExpectedRequest(
        String method,
        URI uri,
        Map<String, String> headers,
        boolean kerberosNegotiate,
        VaultHttpResponse response
    ) {
    }
}
