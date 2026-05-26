package com.example.tdsweb.tds;

record VaultHttpResponse(int statusCode, String body) {
    boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }
}
