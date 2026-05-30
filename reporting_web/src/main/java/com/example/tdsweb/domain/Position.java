package com.example.tdsweb.domain;

public record Position(
    String position,
    String currency,
    long totalLong,
    long totalShort,
    long intradayLong,
    long intradayShort
) {
}
