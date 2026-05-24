package com.example.tdsweb.tds;

import com.example.tdsweb.domain.ClientCandidate;
import com.example.tdsweb.domain.ClientQueryResult;
import com.example.tdsweb.domain.CustomerFundRecord;
import com.example.tdsweb.domain.CustomerHoldRecord;
import com.example.tdsweb.domain.CustomerHoldRecord.Direction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class StubTdsQueryClient implements TdsQueryClient {
    private static final int DEFAULT_TRADE_DATE = 20260418;

    private final TdsRecordMapper mapper;
    private final List<CustomerFundRecord> fundRecords;
    private final List<CustomerHoldRecord> holdRecords;

    public StubTdsQueryClient(TdsRecordMapper mapper) {
        this.mapper = mapper;
        this.fundRecords = createFundRecords();
        this.holdRecords = createHoldRecords();
    }

    @Override
    public List<ClientCandidate> searchClients(String query) {
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        return fundRecords.stream()
            .filter(record -> record.clientId().toLowerCase(Locale.ROOT).contains(normalized)
                || record.clientName().toLowerCase(Locale.ROOT).contains(normalized))
            .map(record -> new ClientCandidate(record.clientId(), record.clientName()))
            .distinct()
            .toList();
    }

    @Override
    public ClientQueryResult queryClient(String clientId, Optional<Integer> tradeDate) {
        int resolvedTradeDate = tradeDate.orElse(DEFAULT_TRADE_DATE);
        return mapper.toClientQueryResult(clientId, resolvedTradeDate, fundRecords, holdRecords);
    }

    private static List<CustomerFundRecord> createFundRecords() {
        return List.of(
            new CustomerFundRecord(
                DEFAULT_TRADE_DATE,
                "1001",
                "Alpha Capital",
                "FA1001",
                "CNY",
                new BigDecimal("100000000.00"),
                new BigDecimal("0.00"),
                new BigDecimal("100000000.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00")
            ),
            new CustomerFundRecord(
                DEFAULT_TRADE_DATE,
                "1002",
                "Beta Futures",
                "FA1002",
                "CNY",
                new BigDecimal("2000000.00"),
                new BigDecimal("-500.25"),
                new BigDecimal("1500000.00"),
                new BigDecimal("0.08"),
                new BigDecimal("0.11")
            )
        );
    }

    private static List<CustomerHoldRecord> createHoldRecords() {
        return List.of(
            new CustomerHoldRecord(DEFAULT_TRADE_DATE, "1001", "CNY", "ag2703", Direction.SHORT, 100, 0),
            new CustomerHoldRecord(DEFAULT_TRADE_DATE, "1001", "CNY", "ag2704", Direction.SHORT, 100, 0),
            new CustomerHoldRecord(DEFAULT_TRADE_DATE, "1001", "CNY", "au2612", Direction.SHORT, 100, 100),
            new CustomerHoldRecord(DEFAULT_TRADE_DATE, "1001", "CNY", "au2703", Direction.SHORT, 100, 0),
            new CustomerHoldRecord(DEFAULT_TRADE_DATE, "1001", "CNY", "au2704", Direction.SHORT, 100, 0),
            new CustomerHoldRecord(DEFAULT_TRADE_DATE, "1001", "CNY", "b2610", Direction.LONG, 100, 0),
            new CustomerHoldRecord(DEFAULT_TRADE_DATE, "1002", "CNY", "cu2601", Direction.LONG, 3, 1)
        );
    }
}
