package com.example.tdsweb.domain;

import java.util.List;

public record ClientQueryResult(
    String clientId,
    int tradeDate,
    ClientSummary summary,
    List<Position> positions
) {
}
