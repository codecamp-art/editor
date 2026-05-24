package com.example.tdsweb.tds;

import com.example.tdsweb.domain.ClientQueryResult;
import com.example.tdsweb.domain.ClientSummary;
import com.example.tdsweb.domain.CustomerFundRecord;
import com.example.tdsweb.domain.CustomerHoldRecord;
import com.example.tdsweb.domain.CustomerHoldRecord.Direction;
import com.example.tdsweb.domain.Position;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TdsRecordMapper {
    public ClientQueryResult toClientQueryResult(
        String clientId,
        int tradeDate,
        List<CustomerFundRecord> fundRecords,
        List<CustomerHoldRecord> holdRecords
    ) {
        List<CustomerFundRecord> matchingFunds = fundRecords.stream()
            .filter(record -> clientId.equals(record.clientId()))
            .sorted(Comparator.comparing(CustomerFundRecord::currency).thenComparing(CustomerFundRecord::fundAccountNo))
            .toList();
        if (matchingFunds.isEmpty()) {
            throw new TdsClientException("client not found: " + clientId);
        }

        String currency = matchingFunds.getFirst().currency();
        List<CustomerFundRecord> sameCurrencyFunds = matchingFunds.stream()
            .filter(record -> currency.equals(record.currency()))
            .toList();
        if (sameCurrencyFunds.size() != matchingFunds.size()) {
            throw new TdsClientException("multiple currencies returned for client: " + clientId);
        }

        ClientSummary summary = summarize(clientId, currency, sameCurrencyFunds);
        List<Position> positions = aggregatePositions(clientId, currency, holdRecords);
        return new ClientQueryResult(clientId, tradeDate, summary, positions);
    }

    private static ClientSummary summarize(String clientId, String currency, List<CustomerFundRecord> records) {
        CustomerFundRecord first = records.getFirst();
        BigDecimal totalEquity = BigDecimal.ZERO;
        BigDecimal mtmPnl = BigDecimal.ZERO;
        BigDecimal marginAvailable = BigDecimal.ZERO;
        BigDecimal riskRatio1 = BigDecimal.ZERO;
        BigDecimal riskRatio2 = BigDecimal.ZERO;

        for (CustomerFundRecord record : records) {
            totalEquity = totalEquity.add(record.dynamicRights());
            mtmPnl = mtmPnl.add(record.holdProfit());
            marginAvailable = marginAvailable.add(record.availableFund());
            riskRatio1 = riskRatio1.max(record.riskDegree1());
            riskRatio2 = riskRatio2.max(record.riskDegree2());
        }

        return new ClientSummary(
            clientId,
            first.clientName(),
            first.fundAccountNo(),
            currency,
            marginAvailable,
            totalEquity,
            mtmPnl,
            riskRatio1,
            riskRatio2,
            riskRatio1
        );
    }

    private static List<Position> aggregatePositions(
        String clientId,
        String currency,
        List<CustomerHoldRecord> holdRecords
    ) {
        Map<String, PositionTotals> byContract = new LinkedHashMap<>();
        holdRecords.stream()
            .filter(record -> clientId.equals(record.clientId()))
            .filter(record -> currency.equals(record.currency()))
            .sorted(Comparator.comparing(CustomerHoldRecord::contractCode))
            .forEach(record -> byContract
                .computeIfAbsent(record.contractCode(), ignored -> new PositionTotals())
                .add(record));

        return byContract.entrySet().stream()
            .map(entry -> entry.getValue().toPosition(entry.getKey(), currency))
            .toList();
    }

    private static final class PositionTotals {
        private long totalLong;
        private long totalShort;
        private long intradayLong;
        private long intradayShort;

        void add(CustomerHoldRecord record) {
            if (record.direction() == Direction.LONG) {
                totalLong += record.holdQuantity();
                intradayLong += record.todayHoldQuantity();
            } else {
                totalShort += record.holdQuantity();
                intradayShort += record.todayHoldQuantity();
            }
        }

        Position toPosition(String contractCode, String currency) {
            return new Position(contractCode, currency, totalLong, totalShort, intradayLong, intradayShort);
        }
    }
}
