package com.example.tdsweb.web;

import com.example.tdsweb.tds.TdsClientException;
import com.example.tdsweb.tds.TdsSecretSanitizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RestExceptionHandler {
    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> invalidRequest(InvalidRequestException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(ex.getMessage()));
    }

    @ExceptionHandler(TdsClientException.class)
    ResponseEntity<ApiError> tdsClientError(TdsClientException ex) {
        HttpStatus status = ex.getMessage() != null && ex.getMessage().startsWith("client not found:")
            ? HttpStatus.NOT_FOUND
            : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(ApiError.of(TdsSecretSanitizer.sanitize(ex.getMessage())));
    }
}
