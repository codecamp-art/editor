package com.example.tdsweb.tds;

import com.example.tdsweb.config.TdsProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

class JdkVaultHttpClient implements VaultHttpClient {
    private final TdsProperties.Vault vault;

    JdkVaultHttpClient(TdsProperties.Vault vault) {
        this.vault = vault;
    }

    @Override
    public VaultHttpResponse request(String method, URI uri, Map<String, String> headers, boolean kerberosNegotiate)
        throws IOException {
        if (kerberosNegotiate) {
            enableTicketCacheLookup();
        }

        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(vault.getConnectTimeoutMs());
        connection.setReadTimeout(vault.getReadTimeoutMs());
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }

        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(new byte[0]);
            }
        }

        int statusCode = connection.getResponseCode();
        String body = readBody(statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream());
        return new VaultHttpResponse(statusCode, body);
    }

    private static void enableTicketCacheLookup() {
        if (System.getProperty("javax.security.auth.useSubjectCredsOnly") == null) {
            System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
