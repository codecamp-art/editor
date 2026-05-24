package com.example.tdsweb.tds;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.tdsweb.domain.ClientQueryResult;
import com.example.tdsweb.domain.CustomerFundRecord;
import com.example.tdsweb.domain.CustomerHoldRecord;
import com.example.tdsweb.domain.CustomerHoldRecord.Direction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TdsRecordMapperTest {
    private final TdsRecordMapper mapper = new TdsRecordMapper();

    @Test
    void aggregatesSummaryAndPositions() {
        List<CustomerFundRecord> funds = List.of(
            fund("1001", "FA1", "CNY", "100.00", "10.00", "80.00", "0.10", "0.20"),
            fund("1001", "FA2", "CNY", "50.00", "-2.00", "40.00", "0.15", "0.18"),
            fund("1002", "FA3", "CNY", "999.00", "0.00", "999.00", "0.01", "0.01")
        );
        List<CustomerHoldRecord> holds = List.of(
            hold("1001", "CNY", "ag2703", Direction.LONG, 2, 1),
            hold("1001", "CNY", "ag2703", Direction.SHORT, 5, 3),
            hold("1001", "CNY", "au2612", Direction.SHORT, 7, 4),
            hold("1002", "CNY", "ag2703", Direction.LONG, 99, 99)
        );

        ClientQueryResult result = mapper.toClientQueryResult("1001", 20260418, funds, holds);

        assertThat(result.summary().totalEquity()).isEqualByComparingTo("150.00");
        assertThat(result.summary().mtmPnl()).isEqualByComparingTo("8.00");
        assertThat(result.summary().marginAvailable()).isEqualByComparingTo("120.00");
        assertThat(result.summary().riskRatio1()).isEqualByComparingTo("0.15");
        assertThat(result.summary().riskRatio2()).isEqualByComparingTo("0.20");
        assertThat(result.positions()).hasSize(2);
        assertThat(result.positions().getFirst().position()).isEqualTo("ag2703");
        assertThat(result.positions().getFirst().totalLong()).isEqualTo(2);
        assertThat(result.positions().getFirst().totalShort()).isEqualTo(5);
        assertThat(result.positions().getFirst().intradayLong()).isEqualTo(1);
        assertThat(result.positions().getFirst().intradayShort()).isEqualTo(3);
    }

    private static CustomerFundRecord fund(
        String clientId,
        String fundAccountNo,
        String currency,
        String dynamicRights,
        String holdProfit,
        String availableFund,
        String risk1,
        String risk2
    ) {
        return new CustomerFundRecord(
            20260418,
            clientId,
            "Alpha Capital",
            fundAccountNo,
            currency,
            new BigDecimal(dynamicRights),
            new BigDecimal(holdProfit),
            new BigDecimal(availableFund),
            new BigDecimal(risk1),
            new BigDecimal(risk2)
        );
    }

    private static CustomerHoldRecord hold(
        String clientId,
        String currency,
        String contractCode,
        Direction direction,
        long holdQuantity,
        long todayHoldQuantity
    ) {
        return new CustomerHoldRecord(20260418, clientId, currency, contractCode, direction, holdQuantity, todayHoldQuantity);
    }
}
