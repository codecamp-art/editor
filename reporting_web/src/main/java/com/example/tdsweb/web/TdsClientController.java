package com.example.tdsweb.web;

import com.example.tdsweb.domain.ClientCandidate;
import com.example.tdsweb.domain.ClientQueryResult;
import com.example.tdsweb.tds.TdsQueryClient;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tds/clients")
public class TdsClientController {
    private final TdsQueryClient tdsQueryClient;

    public TdsClientController(TdsQueryClient tdsQueryClient) {
        this.tdsQueryClient = tdsQueryClient;
    }

    @GetMapping(params = "query")
    List<ClientCandidate> searchClients(
        @RequestParam("query") String query
    ) {
        String normalized = requireText(query, "query");
        return tdsQueryClient.searchClients(normalized);
    }

    @GetMapping("/{clientId}")
    ClientQueryResult queryClient(
        @PathVariable String clientId,
        @RequestParam(value = "tradeDate", required = false) String tradeDate
    ) {
        String normalizedClientId = requireText(clientId, "clientId");
        Optional<Integer> parsedTradeDate = parseTradeDate(tradeDate);
        return tdsQueryClient.queryClient(normalizedClientId, parsedTradeDate);
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new InvalidRequestException(field + " is required");
        }
        return normalized;
    }

    private static Optional<Integer> parseTradeDate(String tradeDate) {
        if (tradeDate == null || tradeDate.isBlank()) {
            return Optional.empty();
        }
        String normalized = tradeDate.trim();
        if (!normalized.matches("\\d{8}")) {
            throw new InvalidRequestException("tradeDate must use YYYYMMDD format");
        }
        int year = Integer.parseInt(normalized.substring(0, 4));
        int month = Integer.parseInt(normalized.substring(4, 6));
        int day = Integer.parseInt(normalized.substring(6, 8));
        try {
            LocalDate.of(year, month, day);
        } catch (DateTimeException ex) {
            throw new InvalidRequestException("tradeDate must be a valid date");
        }
        return Optional.of(Integer.parseInt(normalized));
    }
}
