package com.example.tdsweb.domain;

public record CustomerHoldRecord(
    int tradeDate,
    String clientId,
    String currency,
    String contractCode,
    Direction direction,
    long holdQuantity,
    long todayHoldQuantity
) {
    public enum Direction {
        LONG,
        SHORT
    }
}
