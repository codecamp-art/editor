package com.example.tdsweb.tds;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

interface VaultHttpClient {
    VaultHttpResponse request(String method, URI uri, Map<String, String> headers, boolean kerberosNegotiate)
        throws IOException;
}
