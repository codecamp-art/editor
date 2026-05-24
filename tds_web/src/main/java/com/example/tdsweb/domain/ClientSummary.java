package com.example.tdsweb.domain;

import java.math.BigDecimal;

public record ClientSummary(
    String clientId,
    String clientName,
    String fundAccountNo,
    String currency,
    BigDecimal marginAvailable,
    BigDecimal totalEquity,
    BigDecimal mtmPnl,
    BigDecimal riskRatio1,
    BigDecimal riskRatio2,
    BigDecimal riskRatio
) {
}
