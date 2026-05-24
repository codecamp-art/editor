package com.example.tdsweb.domain;

import java.math.BigDecimal;

public record CustomerFundRecord(
    int tradeDate,
    String clientId,
    String clientName,
    String fundAccountNo,
    String currency,
    BigDecimal dynamicRights,
    BigDecimal holdProfit,
    BigDecimal availableFund,
    BigDecimal riskDegree1,
    BigDecimal riskDegree2
) {
}
