package com.example.tdsweb.web;

import java.time.Instant;

public record ApiError(String message, Instant timestamp) {
    static ApiError of(String message) {
        return new ApiError(message, Instant.now());
    }
}
