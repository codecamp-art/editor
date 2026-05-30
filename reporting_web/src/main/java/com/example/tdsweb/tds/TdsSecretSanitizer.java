package com.example.tdsweb.tds;

import java.util.regex.Pattern;

public final class TdsSecretSanitizer {
    private static final Pattern PASSWORD_PATTERN =
        Pattern.compile("(?i)(password|passwd|token|client_token)\\s*[=:]\\s*[^\\s,;]+");

    private TdsSecretSanitizer() {
    }

    public static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "TDS request failed";
        }
        return PASSWORD_PATTERN.matcher(message).replaceAll("$1=<redacted>");
    }
}
