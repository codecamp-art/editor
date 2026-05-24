package com.example.tdsweb.tds;

public class TdsClientException extends RuntimeException {
    public TdsClientException(String message) {
        super(message);
    }

    public TdsClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
