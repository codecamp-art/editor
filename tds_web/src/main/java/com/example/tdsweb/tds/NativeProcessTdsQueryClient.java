package com.example.tdsweb.tds;

import com.example.tdsweb.config.TdsProperties;
import com.example.tdsweb.domain.ClientCandidate;
import com.example.tdsweb.domain.ClientQueryResult;
import com.example.tdsweb.domain.CustomerFundRecord;
import com.example.tdsweb.domain.CustomerHoldRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class NativeProcessTdsQueryClient implements TdsQueryClient {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);

    private final TdsProperties properties;
    private final TdsRecordMapper mapper;
    private final ObjectMapper objectMapper;

    public NativeProcessTdsQueryClient(TdsProperties properties, TdsRecordMapper mapper, ObjectMapper objectMapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ClientCandidate> searchClients(String query) {
        NativeSearchResponse response = runNative(
            NativeSearchResponse.class,
            "search",
            "--query", query
        );
        return response.clients();
    }

    @Override
    public ClientQueryResult queryClient(String clientId, Optional<Integer> tradeDate) {
        List<String> args = new ArrayList<>();
        args.add("detail");
        args.add("--client-id");
        args.add(clientId);
        tradeDate.ifPresent(value -> {
            args.add("--trade-date");
            args.add(value.toString());
        });

        NativeDetailResponse response = runNative(NativeDetailResponse.class, args.toArray(String[]::new));
        return mapper.toClientQueryResult(clientId, response.tradeDate(), response.fundRecords(), response.holdRecords());
    }

    private <T> T runNative(Class<T> type, String... args) {
        List<String> command = new ArrayList<>();
        command.add(properties.getNativeAdapter().getExecutable().toString());
        command.add("--drtp-endpoints");
        command.add(properties.formattedDrtpEndpoints());
        command.add("--user");
        command.add(properties.getUser());
        command.add("--req-timeout-ms");
        command.add(Integer.toString(properties.getReqTimeoutMs()));
        command.add("--log-level");
        command.add(Integer.toString(properties.getLogLevel()));
        command.add("--klg-enable");
        command.add(Boolean.toString(properties.isKlgEnable()));
        command.add("--function-no");
        command.add(Integer.toString(properties.getFunctionNo()));
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("TDS_WEB_TDS_PASSWORD", properties.getPassword());
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TdsClientException("native TDS adapter timed out");
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new TdsClientException(TdsSecretSanitizer.sanitize("native TDS adapter failed: " + stderr));
            }
            return objectMapper.readValue(stdout, type);
        } catch (IOException ex) {
            throw new TdsClientException("failed to execute native TDS adapter", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TdsClientException("interrupted while waiting for native TDS adapter", ex);
        }
    }

    private record NativeSearchResponse(List<ClientCandidate> clients) {
    }

    private record NativeDetailResponse(
        int tradeDate,
        List<CustomerFundRecord> fundRecords,
        List<CustomerHoldRecord> holdRecords
    ) {
    }
}
