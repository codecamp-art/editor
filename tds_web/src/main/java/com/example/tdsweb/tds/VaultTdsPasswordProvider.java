package com.example.tdsweb.tds;

import com.example.tdsweb.config.TdsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public class VaultTdsPasswordProvider implements TdsPasswordProvider {
    private static final String LOGIN_PATH = "auth/kerberos/login";

    private final TdsProperties.Vault vault;
    private final ObjectMapper objectMapper;
    private final VaultHttpClient httpClient;
    private volatile String cachedPassword;

    public VaultTdsPasswordProvider(TdsProperties properties, ObjectMapper objectMapper) {
        this(properties.getVault(), objectMapper, new JdkVaultHttpClient(properties.getVault()));
    }

    VaultTdsPasswordProvider(TdsProperties.Vault vault, ObjectMapper objectMapper, VaultHttpClient httpClient) {
        this.vault = vault;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getPassword() {
        String password = cachedPassword;
        if (password != null) {
            return password;
        }

        synchronized (this) {
            if (cachedPassword == null) {
                cachedPassword = readPasswordFromVault();
            }
            return cachedPassword;
        }
    }

    private String readPasswordFromVault() {
        requireConfigured(vault.getAddress(), "VAULT_ADDR or tds.vault.address");
        requireConfigured(vault.getSecretEngine(), "VAULT_SECRET_ENGINE or tds.vault.secret-engine");
        requireConfigured(vault.getSecretPath(), "VAULT_SECRET_PATH or tds.vault.secret-path");
        requireConfigured(vault.getSecretKey(), "VAULT_SECRET_KEY or tds.vault.secret-key");

        String token = loginToVault();
        try {
            VaultHttpResponse response = request(
                "GET",
                vaultApiUri(vaultKvV2SecretPath()),
                vaultHeadersWithToken(token),
                false
            );
            if (!response.isSuccessful()) {
                throw new TdsClientException("vault HTTP read failed for TDS password: HTTP status " + response.statusCode());
            }

            JsonNode secretValue = parseJson(response.body())
                .path("data")
                .path("data")
                .path(vault.getSecretKey());
            if (!secretValue.isTextual() || secretValue.asText().isEmpty()) {
                throw new TdsClientException("vault HTTP read failed for TDS password: field not found in vault response");
            }
            return secretValue.asText();
        } finally {
            token = null;
        }
    }

    private String loginToVault() {
        VaultHttpResponse response = request("POST", vaultApiUri(LOGIN_PATH), vaultHeaders(), true);
        if (!response.isSuccessful()) {
            throw new TdsClientException("vault kerberos HTTP login failed: HTTP status " + response.statusCode());
        }

        JsonNode token = parseJson(response.body()).path("auth").path("client_token");
        if (!token.isTextual() || token.asText().isEmpty()) {
            throw new TdsClientException("vault kerberos HTTP login did not return auth.client_token");
        }
        return token.asText();
    }

    private VaultHttpResponse request(String method, URI uri, Map<String, String> headers, boolean kerberosNegotiate) {
        try {
            return httpClient.request(method, uri, headers, kerberosNegotiate);
        } catch (IOException ex) {
            throw new TdsClientException(
                TdsSecretSanitizer.sanitize("vault HTTP request failed for TDS password: " + ex.getMessage()),
                ex
            );
        }
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException ex) {
            throw new TdsClientException("vault HTTP response for TDS password is not valid JSON", ex);
        }
    }

    private Map<String, String> vaultHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!isUnset(vault.getNamespace())) {
            headers.put("X-Vault-Namespace", vault.getNamespace());
        }
        return headers;
    }

    private Map<String, String> vaultHeadersWithToken(String token) {
        Map<String, String> headers = vaultHeaders();
        headers.put("X-Vault-Token", token);
        return headers;
    }

    private URI vaultApiUri(String apiPath) {
        String address = trimTrailingSlashes(vault.getAddress());
        return URI.create(address + "/v1/" + trimSlashes(apiPath));
    }

    private String vaultKvV2SecretPath() {
        return trimSlashes(vault.getSecretEngine()) + "/data/" + trimSlashes(vault.getSecretPath());
    }

    private static void requireConfigured(String value, String name) {
        if (isUnset(value)) {
            throw new TdsClientException(name + " is required for vault-backed TDS password");
        }
    }

    private static boolean isUnset(String value) {
        return value == null || value.isBlank() || value.startsWith("REPLACE_ME");
    }

    private static String trimSlashes(String value) {
        String trimmed = value.strip();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimTrailingSlashes(trimmed);
    }

    private static String trimTrailingSlashes(String value) {
        String trimmed = value.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
